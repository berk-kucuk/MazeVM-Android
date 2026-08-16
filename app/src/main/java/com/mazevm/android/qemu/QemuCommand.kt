package com.mazevm.android.qemu

import com.mazevm.android.data.model.Arch
import com.mazevm.android.data.model.DisplayMode
import com.mazevm.android.data.model.VmConfig
import java.io.File

/**
 * Sockets a running machine exposes. Unix sockets are used rather than TCP ports so
 * nothing is reachable from outside the app sandbox and no port allocation is needed.
 */
data class VmSockets(
    val qmp: File,
    val vnc: File?,
)

/** Builds the QEMU argument vector for a machine. Pure; easy to unit-test and to log. */
object QemuCommand {

    fun build(
        vm: VmConfig,
        runtime: QemuRuntime,
        sockets: VmSockets,
    ): List<String> = buildList {
        add(runtime.systemBinary(vm.arch).absolutePath)

        add("-name"); add(vm.name)

        // Start from nothing so the machine is exactly what the config describes.
        add("-nodefaults")
        add("-L"); add(runtime.dataDir.absolutePath)

        addAll(machineArgs(vm))
        addAll(cpuArgs(vm))
        addAll(firmwareArgs(vm, runtime))
        addAll(storageArgs(vm))
        addAll(directKernelArgs(vm))
        addAll(networkArgs(vm))
        addAll(displayArgs(vm, sockets))
        addAll(serialArgs(vm))

        add("-device"); add("virtio-rng-pci")
        add("-rtc"); add("base=utc,driftfix=slew")

        add("-qmp"); add("unix:${sockets.qmp.absolutePath},server=on,wait=off")

        addAll(tokenizeArguments(vm.extraArgs))
    }

    private fun machineArgs(vm: VmConfig): List<String> = when (vm.arch) {
        Arch.AARCH64 -> listOf(
            "-machine", "${vm.machineType},gic-version=max,virtualization=off",
        )
        Arch.X86_64 -> listOf("-machine", vm.machineType)
    }

    private fun cpuArgs(vm: VmConfig): List<String> = buildList {
        add("-cpu"); add(vm.cpuModel)
        add("-smp"); add(vm.cpuCount.toString())
        add("-m"); add("${vm.ramMb}M")
        // Android never grants KVM to an unprivileged app, so this is always software
        // emulation. Multi-threaded TCG at least spreads vCPUs across host cores.
        add("-accel"); add("tcg,thread=multi,tb-size=${tbSizeMb(vm.ramMb)}")
    }

    /** Translation-block cache. Too small thrashes; too large wastes scarce RAM. */
    private fun tbSizeMb(ramMb: Int): Int = ramMb.coerceIn(512, 4096) / 8

    private fun firmwareArgs(vm: VmConfig, runtime: QemuRuntime): List<String> {
        if (!vm.useUefi || vm.arch != Arch.AARCH64) return emptyList()
        val code = runtime.firmwareCode() ?: return emptyList()
        val vars = runtime.firmwareVars(vm.id)
        return listOf(
            "-drive", "if=pflash,format=raw,unit=0,readonly=on,file=${code.absolutePath}",
            "-drive", "if=pflash,format=raw,unit=1,file=${vars.absolutePath}",
        )
    }

    private fun storageArgs(vm: VmConfig): List<String> = buildList {
        val hasCdrom = vm.cdromPath != null
        // With an installer disc attached the disc has to win, otherwise the machine
        // boots the half-installed disk instead.
        val diskBootIndex = if (hasCdrom) 1 else 0

        vm.diskPath?.let { path ->
            val snapshot = if (vm.snapshotMode) ",snapshot=on" else ""
            add("-drive")
            add(
                "if=none,id=hd0,file=$path,format=${vm.diskFormat.qemuName}," +
                    "cache=writeback,discard=unmap,aio=threads$snapshot"
            )
            add("-device"); add("virtio-blk-pci,drive=hd0,bootindex=$diskBootIndex")
        }

        vm.cdromPath?.let { path ->
            add("-drive"); add("if=none,id=cd0,file=$path,format=raw,media=cdrom,readonly=on")
            add("-device"); add("virtio-scsi-pci,id=scsi0")
            add("-device"); add("scsi-cd,drive=cd0,bus=scsi0.0,bootindex=0")
        }

        vm.payloadPath?.let { path ->
            // Presented as a plain raw disk so the guest can stream it with tar.
            add("-drive"); add("if=none,id=payload,file=$path,format=raw,readonly=on")
            add("-device"); add("virtio-blk-pci,drive=payload")
        }

        vm.cloudInitSeedPath?.let { path ->
            // cloud-init's NoCloud source scans every block device for a filesystem
            // labelled "cidata", so a plain virtio disk is enough.
            add("-drive"); add("if=none,id=seed,file=$path,format=raw,readonly=on")
            add("-device"); add("virtio-blk-pci,drive=seed")
        }
    }

    private fun directKernelArgs(vm: VmConfig): List<String> = buildList {
        vm.kernelPath?.let { add("-kernel"); add(it) }
        vm.initrdPath?.let { add("-initrd"); add(it) }
        vm.kernelAppend?.takeIf { it.isNotBlank() }?.let { add("-append"); add(it) }
    }

    private fun networkArgs(vm: VmConfig): List<String> {
        if (!vm.networkEnabled) return listOf("-nic", "none")
        val forwards = vm.portForwards.joinToString("") {
            ",hostfwd=${it.protocol}::${it.hostPort}-:${it.guestPort}"
        }
        return listOf(
            // User-mode networking needs no privileges, which is the only option here.
            "-netdev", "user,id=net0$forwards",
            "-device", "virtio-net-pci,netdev=net0",
        )
    }

    private fun displayArgs(vm: VmConfig, sockets: VmSockets): List<String> = buildList {
        // The framebuffer is always drawn by the in-app VNC client, never by QEMU's
        // own GTK/SDL front-ends, which do not exist on Android.
        add("-display"); add("none")

        if (!vm.display.hasVnc || sockets.vnc == null) {
            add("-vga"); add("none")
            return@buildList
        }

        add("-vnc"); add("unix:${sockets.vnc.absolutePath}")
        add("-device"); add(vgaDevice(vm))
        // A tablet reports absolute coordinates, which is what a touchscreen produces.
        add("-device"); add("qemu-xhci,id=usb")
        add("-device"); add("usb-tablet,bus=usb.0")
        add("-device"); add("usb-kbd,bus=usb.0")
    }

    private fun vgaDevice(vm: VmConfig): String = when (vm.vgaDevice) {
        "ramfb" -> "ramfb"
        "virtio-gpu-pci" -> "virtio-gpu-pci,xres=${vm.screenWidth},yres=${vm.screenHeight}"
        else -> vm.vgaDevice
    }

    private fun serialArgs(vm: VmConfig): List<String> {
        if (!vm.display.hasSerial) return listOf("-serial", "none")
        // Serial rides on the process pipes: QEMU's stdout is the console output and
        // its stdin is the keyboard. Control still goes through QMP, so the monitor is
        // deliberately left off the same stream.
        return listOf("-serial", "stdio", "-monitor", "none")
    }

    /**
     * Splits a user-supplied argument string the way a shell would for the simple
     * cases: whitespace separates, and single or double quotes group.
     */
    fun tokenizeArguments(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var pending = false

        for (ch in raw) {
            when {
                quote != null && ch == quote -> quote = null
                quote != null -> current.append(ch)
                ch == '"' || ch == '\'' -> { quote = ch; pending = true }
                ch.isWhitespace() -> {
                    if (current.isNotEmpty() || pending) {
                        tokens += current.toString()
                        current.setLength(0)
                        pending = false
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty() || pending) tokens += current.toString()
        return tokens
    }

    /** Human-readable form for the log screen and bug reports. */
    fun render(argv: List<String>): String = argv.joinToString(" ") { arg ->
        if (arg.any { it.isWhitespace() }) "'$arg'" else arg
    }
}
