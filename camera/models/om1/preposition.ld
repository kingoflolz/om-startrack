OUTPUT_FORMAT("elf32-mn10300")
OUTPUT_ARCH(mn10300)
ENTRY(pre_envelope_adopt)
PROVIDE(FW_PRE_ADOPT = 0x40008c4f);
PROVIDE(FW_FINAL_REPLAY_CONTINUE = 0x4001f0a4);
PROVIDE(FW_FINAL_COUPLED = 0x4000bd28);
PROVIDE(FW_RESTORE_BOUNDS = 0x4001926e);
PROVIDE(FW_AFTER_FINAL_BUILDER = 0x4001f0d2);
SECTIONS
{
    .pre_envelope_text 0x40008cc1 : { KEEP(*(.pre_envelope_text)) }
    .pre_envelope_adopt_redirect 0x40008c09 : { KEEP(*(.pre_envelope_adopt_redirect)) }
    .pre_envelope_builder_redirect 0x4001f09f : { KEEP(*(.pre_envelope_builder_redirect)) }
    /DISCARD/ : { *(.comment) *(.note*) *(.data) *(.bss) }
}
ASSERT(SIZEOF(.pre_envelope_text) <= 0x13f, "PRE envelope exceeds unused old PRE cave tail")
ASSERT(SIZEOF(.pre_envelope_adopt_redirect) == 5, "PRE adoption CALL width changed")
ASSERT(SIZEOF(.pre_envelope_builder_redirect) == 5, "stock builder entry width changed")
