/* Input-dependent constants are populated by patch_firmware.py. */
.section .omstar_input,"a",%progbits
.balign 4
.global astro_assist_acceleration_commit_anchor
astro_assist_acceleration_commit_anchor:
.zero 32
.global astro_assist_acceleration_remap_anchor
astro_assist_acceleration_remap_anchor:
.zero 32
.section .note.GNU-stack,"",%progbits
