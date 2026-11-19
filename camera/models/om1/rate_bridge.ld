OUTPUT_FORMAT("elf32-mn10300")
OUTPUT_ARCH(mn10300)
ENTRY(astro_op37_bridge_wrapper)

/* Frozen stock opcode-0x37 handler; this is a call target, not a hunk. */
PROVIDE(FW_STOCK_OP37_HANDLER = 0x4001384e);

SECTIONS
{
    /* Verified-erased 128-byte cave immediately below the former receiver. */
    .astro_op37_bridge_text 0x40008b80 :
    {
        KEEP(*(.astro_op37_bridge.text))
    }

    /* Stock opcode-0x37 handler-table word (stock value 0x4001384e). */
    .astro_op37_bridge_dispatch 0x4001fe48 :
    {
        KEEP(*(.astro_op37_bridge.dispatch))
    }

    /DISCARD/ :
    {
        *(.comment)
        *(.note*)
        *(.data)
        *(.bss)
    }
}

ASSERT(ADDR(.astro_op37_bridge_text) == 0x40008b80,
       "compact opcode-0x37 bridge moved out of its audited cave")
ASSERT(SIZEOF(.astro_op37_bridge_text) <= 0x80,
       "compact opcode-0x37 bridge exceeds 0x40008b80..0x40008bff")
ASSERT(ADDR(.astro_op37_bridge_dispatch) == 0x4001fe48,
       "opcode-0x37 dispatch word moved")
ASSERT(SIZEOF(.astro_op37_bridge_dispatch) == 4,
       "opcode-0x37 dispatch replacement must remain exactly four bytes")
