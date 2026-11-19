"""Add the custom services to the launcher read from the input firmware."""


def launcher(stock: str, functions: str, model: str) -> str:
    def replace(text, before, after):
        if text.count(before) != 1:
            raise ValueError(f"unexpected stock launcher anchor: {before!r}")
        return text.replace(before, after)

    result = replace(stock, "wifi_func() {\n", functions.rstrip() + "\n\n" + "wifi_func() {\n")
    comment = ("Reset this model's Linux-to-MAIN mailboxes." if model == "om1ii"
               else "Reset the Linux-to-main astro mailbox before starting its publisher.")
    result = replace(result, "wifi_func() {\n", "wifi_func() {\n\t# " + comment
        + "\n\t/sbin/devmem 0x2a2985fb8 8 0\n\t/sbin/devmem 0x2a2985fbc 8 0\n")
    result = replace(result, "\t/usr/bin/wifi_mdns &\n}",
                     "\t/usr/bin/wifi_mdns &\n\tremote_shell_func\n}")
    result = replace(result, "\t/usr/bin/blebridge &\n",
                     "\tLD_PRELOAD=/usr/lib/libbsa_gatt_shim.so /usr/bin/blebridge &\n\tstar_gatt_func\n")
    if model == "om1ii":
        result = result.replace("0x2a2985fb8", "0x2a29af634").replace("0x2a2985fbc", "0x2a29af638")
    return result
