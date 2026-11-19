OUTPUT_FORMAT("elf32-mn10300")
OUTPUT_ARCH(mn10300)
ENTRY(gyro_reply_dispatch)
PROVIDE(FW_AFTER_SEND = 0x40012d82);
PROVIDE(FW_STOCK_SEND = 0x40014658);
PROVIDE(FW_MEMCPY = 0x4000ad12);
PROVIDE(FW_ALLOC = 0x40018238);
PROVIDE(FW_FREE = 0x40018248);
PROVIDE(FW_IN_INTERRUPT = 0x40024255);
PROVIDE(FW_OUTGOING_QUEUE = 0x40024878);
PROVIDE(FW_QUEUE_TASK = 0x40023a8d);
PROVIDE(FW_QUEUE_INTERRUPT = 0x40022ce3);
PROVIDE(FW_ENVELOPE_COPY_CONTINUE = 0x4000979d);
SECTIONS
{
    .gyro_reply_text 0x40008800 : { KEEP(*(.gyro_reply.text)) }
    .gyro_reply_send_redirect 0x40012d7d : { KEEP(*(.gyro_reply.send_redirect)) }
    .gyro_reply_copy_redirect 0x40014625 : { KEEP(*(.gyro_reply.copy_redirect)) }
    /DISCARD/ : { *(.comment) *(.note*) *(.data) *(.bss) }
}
ASSERT(SIZEOF(.gyro_reply_text) <= 0x380, "reply helper overlaps rate bridge at 0x40008b80")
ASSERT(SIZEOF(.gyro_reply_send_redirect) == 5, "sender redirect must replace one five-byte CALL")
ASSERT(SIZEOF(.gyro_reply_copy_redirect) == 7, "copy redirect must replace one seven-byte CALL")
