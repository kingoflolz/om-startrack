OUTPUT_FORMAT("elf32-mn10300")
OUTPUT_ARCH(mn10300)
ENTRY(gyro_sample_hook)
FW_RELEASE_INTERRUPTS = 0x4000a5fa;
FW_AFTER_RELEASE = 0x4001ee60;
FW_CALIBRATE = 0x40018e17;
FW_AFTER_CALIBRATE = 0x4001ee65;
FW_PRE_MODE_HOOK = 0x40008c88;
FW_AFTER_IRQ_TIMESTAMP = 0x4001f119;

SECTIONS {
    .gyro_text 0x40007000 : {
        KEEP(*(.gyro.hooks))
        *(.text*)
        *(.rodata*)
    }
    .gyro_release_redirect 0x4001ee59 : { KEEP(*(.gyro.release_redirect)) }
    .gyro_sample_redirect 0x4001ee60 : { KEEP(*(.gyro.sample_redirect)) }
    .gyro_mode_redirect 0x4001eab3 : { KEEP(*(.gyro.mode_redirect)) }
    .gyro_irq_redirect 0x4001f110 : { KEEP(*(.gyro.irq_redirect)) }
    .gyro_ring_write 0x4000926d : { KEEP(*(.gyro.ring_write)) }
    .gyro_ring_count 0x400092a9 : { KEEP(*(.gyro.ring_count)) }
    .gyro_ring_oldest 0x400092b4 : { KEEP(*(.gyro.ring_oldest)) }
    .gyro_ring_dump 0x400094ab : { KEEP(*(.gyro.ring_dump)) }
    .unexpected_data : { *(.data*) *(.bss*) *(COMMON) }
    /DISCARD/ : { *(.comment) *(.note*) *(.eh_frame*) }
}
ASSERT(SIZEOF(.gyro_text) <= 0x1800, "gyro code exceeds 0x40007000..0x400087ff")
ASSERT(SIZEOF(.unexpected_data) == 0, "gyro code requires unallocated data/BSS")
ASSERT(SIZEOF(.gyro_release_redirect) == 7, "release redirect width")
ASSERT(SIZEOF(.gyro_sample_redirect) == 5, "sample redirect width")
ASSERT(SIZEOF(.gyro_mode_redirect) == 9, "mode redirect width")
ASSERT(SIZEOF(.gyro_irq_redirect) == 9, "IRQ redirect width")
ASSERT(SIZEOF(.gyro_ring_write) == 2, "ring producer width")
ASSERT(SIZEOF(.gyro_ring_count) == 2, "ring capacity width")
ASSERT(SIZEOF(.gyro_ring_oldest) == 2, "ring oldest width")
ASSERT(SIZEOF(.gyro_ring_dump) == 2, "ring dump width")
