/* Input-dependent constants are populated by patch_firmware.py. */
.section .omstar_input,"a",%progbits
.balign 4
.global kApertureNative85
kApertureNative85:
.zero 80
.global kApertureNative128
kApertureNative128:
.zero 54
.global kApertureNative256
kApertureNative256:
.zero 28
.global kApertureNominalDeci85
kApertureNominalDeci85:
.zero 80
.global kApertureNominalDeci128
kApertureNominalDeci128:
.zero 54
.global kApertureNominalDeci256
kApertureNominalDeci256:
.zero 28
.global kAperturePow2Fraction
kAperturePow2Fraction:
.zero 512
.section .note.GNU-stack,"",%progbits
