#!/system/bin/sh
# Kernel controls for the Galaxy Tab S9 FE+ Wi-Fi (SM-X610).
CPU=/sys/devices/system/cpu/cpufreq
GPU=/sys/kernel/gpu
THERMAL=/sys/class/thermal
ABI=/sys/kernel/fawpie_manager
IO=/sys/block/sda/queue/scheduler
VM=/proc/sys/vm/swappiness
TCP=/proc/sys/net/ipv4/tcp_congestion_control
TCP_LIST=/proc/sys/net/ipv4/tcp_available_congestion_control
STORE=/data/adb/cherry-manager
RAM_PLUS_PROP=prop:persist.sys.zram.ram_expand_size
USER_ID=$(id -u)
BOOT=$(cat /proc/sys/kernel/random/boot_id)
BOARD=$(getprop ro.product.vendor.device)

read_value() {
    if [ -r "$1" ]; then
        tr '\n\t' '  ' < "$1" 2>/dev/null | sed 's/^ *//;s/ *$//'
    fi
}

can_write() {
    [ "$USER_ID" = 0 ] && [ -w "$1" ] && printf 1 || printf 0
}

active_choice() {
    printf '%s\n' "$1" | sed -n 's/^.*\[\([^]]*\)\].*$/\1/p'
}

display_preference() {
    preferred=$(cmd display get-user-preferred-display-mode 0 2>/dev/null)
    case "$preferred" in
        *': null'|*': -1 -1 0.0') printf default ;;
        *': 1600 2560 '*) printf '%s' "${preferred##* }" ;;
    esac
}

setting_path() {
    case "$1" in
        cache-pressure) printf /proc/sys/vm/vfs_cache_pressure ;;
        read-ahead) printf /sys/block/sda/queue/read_ahead_kb ;;
        queue-depth) printf /sys/block/sda/queue/nr_requests ;;
        ecn) printf /proc/sys/net/ipv4/tcp_ecn ;;
        sack) printf /proc/sys/net/ipv4/tcp_sack ;;
        timestamps) printf /proc/sys/net/ipv4/tcp_timestamps ;;
        fast-open) printf /proc/sys/net/ipv4/tcp_fastopen ;;
        window-scale) printf global:window_animation_scale ;;
        transition-scale) printf global:transition_animation_scale ;;
        animator-scale) printf global:animator_duration_scale ;;
        power-saver) printf power-saver ;;
        ram-plus) printf global:ram_expand_size ;;
    esac
}

setting_choices() {
    case "$1" in
        read-ahead) printf '128 256 512 1024 2048 4096' ;;
        queue-depth) printf '16 32 64 128 256 512' ;;
        ecn) printf '0 1 2' ;;
        sack|timestamps|power-saver) printf '0 1' ;;
        fast-open) printf '0 1 2 3' ;;
        window-scale|transition-scale|animator-scale) printf '0.0 0.5 1.0 1.5 2.0' ;;
        ram-plus)
            settings get global ram_expand_size_list 2>&1 |
                awk -F ',' '{for (i = 1; i <= NF; i++) {
                    gsub(/[[:space:]]/, "", $i)
                    if ($i ~ /^[1-9][0-9]*$/) {printf "%s%d", space, $i * 1024; space = " "}
                }}' ;;
    esac
}

report_settings() {
    for key in cache-pressure read-ahead queue-depth ecn sack timestamps fast-open window-scale transition-scale animator-scale power-saver ram-plus; do
        node=$(setting_path "$key")
        current=$(value_at "$node")
        [ -n "$current" ] || continue
        if [ "$key" = ram-plus ]; then
            [ "$BOARD" = gts9fepwifi ] && [ "$current" != null ] &&
                [ -n "$(value_at "$RAM_PLUS_PROP")" ] && [ -n "$(setting_choices ram-plus)" ] || continue
        fi
        case "$node" in
            global:*|power-saver) [ "$USER_ID" = 0 ] && allowed=1 || allowed=0 ;;
            *) allowed=$(can_write "$node") ;;
        esac
        if [ "$key" = ram-plus ] && [ "$(settings get global zram_enabled)" != 1 ]; then allowed=0; fi
        printf 'SYSTEM|%s|%s|%s|%s\n' "$key" "$current" "$(setting_choices "$key")" "$allowed"
    done
}

report_info() {
    printf 'INFO|cpu-online|%s\n' "$(read_value /sys/devices/system/cpu/online)"
    printf 'INFO|zram-size|%s\n' "$(read_value /sys/block/zram0/disksize)"
    printf 'INFO|zram-compressor|%s\n' "$(active_choice "$(read_value /sys/block/zram0/comp_algorithm)")"
    printf 'INFO|zram-stat|%s\n' "$(read_value /sys/block/zram0/mm_stat)"
    for device in "$THERMAL"/cooling_device*; do
        [ -d "$device" ] || continue
        type=$(read_value "$device/type")
        current=$(read_value "$device/cur_state")
        max=$(read_value "$device/max_state")
        [ -n "$type" ] && [ -n "$current" ] && [ -n "$max" ] || continue
        printf 'COOLING|%s|%s|%s\n' "$type" "$current" "$max"
    done
}

report() {
    printf 'DEVICE|%s|%s|%s|%s|%s\n' "$BOARD" "$(uname -r)" \
        "$(read_value "$ABI/abi_version")" "$(read_value "$ABI/variant")" "$USER_ID"
    if [ -f "$ABI/abi_version" ] && [ -f "$ABI/variant" ]; then
        printf 'KERNEL_INTERFACE|present\n'
    fi
    if [ "$USER_ID" = 0 ] && [ "$(read_value "$STORE/boot")" = "$BOOT" ]; then
        printf 'STATE|%s|%s|%s\n' "$(read_value "$STORE/profile")" \
            "$(read_value "$STORE/session")" "$(test -s "$STORE/undo" && printf 1 || printf 0)"
    fi
    for policy in "$CPU"/policy*; do
        [ -d "$policy" ] || continue
        printf 'CPU|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s\n' "${policy##*/}" \
            "$(read_value "$policy/related_cpus")" "$(read_value "$policy/scaling_cur_freq")" \
            "$(read_value "$policy/scaling_min_freq")" "$(read_value "$policy/scaling_max_freq")" \
            "$(read_value "$policy/scaling_governor")" "$(read_value "$policy/scaling_available_frequencies")" \
            "$(read_value "$policy/scaling_available_governors")" \
            "$(can_write "$policy/scaling_max_freq")" "$(can_write "$policy/scaling_governor")" \
            "$(can_write "$policy/scaling_min_freq")"
    done
    if [ -d "$GPU" ]; then
        printf 'GPU|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s\n' "$(read_value "$GPU/gpu_model")" \
            "$(read_value "$GPU/gpu_clock")" "$(read_value "$GPU/gpu_min_clock")" \
            "$(read_value "$GPU/gpu_max_clock")" "$(read_value "$GPU/gpu_governor")" \
            "$(read_value "$GPU/gpu_freq_table")" "$(read_value "$GPU/gpu_busy")" \
            "$(can_write "$GPU/gpu_max_clock")" "$(can_write "$GPU/gpu_min_clock")" \
            "$(read_value "$GPU/gpu_available_governor")" "$(can_write "$GPU/gpu_governor")"
    fi
    for zone in "$THERMAL"/thermal_zone*; do
        [ -d "$zone" ] || continue
        name=$(read_value "$zone/type")
        temp=$(read_value "$zone/temp")
        case "$temp" in ''|*[!0-9-]*) continue ;; esac
        [ -n "$name" ] || continue
        printf 'TEMP|%s|%s|%s\n' "${zone##*/}" "$name" "$temp"
    done
    io=$(read_value "$IO")
    printf 'SYSTEM|io|%s|%s|%s\n' "$(active_choice "$io")" "$(printf '%s' "$io" | tr -d '[]')" "$(can_write "$IO")"
    printf 'SYSTEM|vm|%s||%s\n' "$(read_value "$VM")" "$(can_write "$VM")"
    printf 'SYSTEM|tcp|%s|%s|%s\n' "$(read_value "$TCP")" "$(read_value "$TCP_LIST")" "$(can_write "$TCP")"
    printf 'SYSTEM|display|%s||%s\n' "$(display_preference)" "$(test "$USER_ID" = 0 && printf 1 || printf 0)"
    report_settings
    report_info
    awk '{printf "PRESSURE|%s|%s|%s\n", FILENAME, $1, $2}' /proc/pressure/cpu /proc/pressure/memory 2>/dev/null
    return 0
}

fail() {
    printf '%s\n' "$*" >&2
    exit 1
}

value_at() {
    case "$1" in
        "$IO") active_choice "$(read_value "$IO")" ;;
        display) display_preference ;;
        global:*)
            value=$(settings get global "${1#global:}" 2>&1) && printf '%s' "$value" ;;
        "$RAM_PLUS_PROP") getprop persist.sys.zram.ram_expand_size ;;
        power-saver)
            case "$(settings get global low_power 2>/dev/null)" in
                1) printf 1 ;;
                0|null) printf 0 ;;
            esac ;;
        *) read_value "$1" ;;
    esac
}

commit_value() {
    case "$1" in
        display)
            if [ "$2" = default ]; then
                cmd display clear-user-preferred-display-mode 0 >/dev/null 2>&1 || return 1
            else
                cmd display set-user-preferred-display-mode 1600 2560 "$2" 0 >/dev/null 2>&1 || return 1
            fi ;;
        global:*)
            if [ "$2" = null ]; then
                output=$(settings delete global "${1#global:}" 2>&1) || return 1
            else
                output=$(settings put global "${1#global:}" "$2" 2>&1) || return 1
            fi ;;
        power-saver) cmd power set-mode "$2" >/dev/null 2>&1 || return 1 ;;
        "$RAM_PLUS_PROP") setprop persist.sys.zram.ram_expand_size "$2" >/dev/null 2>&1 || return 1 ;;
        *) [ -w "$1" ] && { printf '%s\n' "$2" > "$1"; } 2>/dev/null || return 1 ;;
    esac
    [ "$(value_at "$1")" = "$2" ]
}

capture_limits() (
    temporary=$(mktemp "$STORE/limits.XXXXXXXX") || exit 1
    trap 'rm -f "$temporary"' EXIT
    if [ "$2" = upgrade ] && [ -s "$1" ]; then
        cat "$1" > "$temporary" || exit 1
    fi
    for node in "$CPU"/policy*/scaling_min_freq "$CPU"/policy*/scaling_max_freq \
        "$CPU"/policy*/scaling_governor "$GPU/gpu_min_clock" "$GPU/gpu_max_clock"; do
        [ -w "$node" ] || continue
        grep -Fq "$node|" "$temporary" && continue
        setting=$(value_at "$node")
        [ -n "$setting" ] || continue
        printf '%s|%s\n' "$node" "$setting" >> "$temporary" || exit 1
    done
    [ -s "$temporary" ] && mv "$temporary" "$1"
)

begin() {
    journal=$(mktemp "$STORE/changes.XXXXXXXX") || fail 'Could not open the change journal.'
    trap 'rm -f "$journal"' EXIT
    trap 'abort_change "The change was interrupted."' HUP INT TERM
}

checked() {
    old=$(value_at "$1")
    [ -n "$old" ] || return 1
    [ "$old" = "$2" ] && return 0
    printf '%s|%s|%s\n' "$1" "$old" "$2" >> "$journal" || return 1
    commit_value "$1" "$2"
}

revert() {
    awk '{line[NR]=$0} END {for(i=NR;i>0;i--) print line[i]}' "$journal" |
        (
            failed=0
            while IFS='|' read -r node before after; do
                [ "$(value_at "$node")" = "$before" ] || commit_value "$node" "$before" || failed=1
            done
            exit "$failed"
        )
}

abort_change() {
    trap - HUP INT TERM
    if revert; then
        fail "$1 Previous values were restored."
    else
        fail "$1 Some previous values could not be restored."
    fi
}

contains() {
    case " $1 " in *" $2 "*) return 0 ;; *) return 1 ;; esac
}

low_cap() {
    awk -v ceiling="$(($2 * $3 / 100))" -v minimum="$4" '
        { for (i=1;i<=NF;i++) if ($i>=minimum && $i<=ceiling && $i>best) best=$i }
        END { if (best) print best; else print minimum }' "$1"
}

recorded_limit() {
    awk -F '|' -v path="$2" '$1 == path { print $2; exit }' "$1"
}

lowest_frequency() {
    awk '{for (i=1;i<=NF;i++) if ($i ~ /^[0-9]+$/ && $i>0 && (!min || $i<min)) min=$i} END {if (min) print min}' "$1"
}

apply_frequency_limits() (
    minimum=$(recorded_limit "$1" "$2")
    maximum=$(recorded_limit "$1" "$3")
    [ -n "$minimum" ] || [ -n "$maximum" ] || exit 0

    current_max=$(read_value "$3")
    [ -n "$current_max" ] || exit 1
    if [ -n "$minimum" ] && [ "$6" = lowest ]; then
        minimum=$(lowest_frequency "$4")
        [ -n "$minimum" ] || exit 1
    fi
    floor="$minimum"
    if [ -z "$floor" ]; then
        floor=$(read_value "$2")
        [ -n "$floor" ] || exit 1
    fi
    if [ -n "$maximum" ] && [ "$5" -lt 100 ]; then
        maximum=$(low_cap "$4" "$maximum" "$5" "$floor")
        [ -n "$maximum" ] || exit 1
    fi
    if [ -n "$maximum" ] && [ "$maximum" -lt "$floor" ]; then
        maximum=$floor
    fi

    # Raise the ceiling first if the desired floor is above the current range.
    if [ -n "$minimum" ] && [ "$minimum" -gt "$current_max" ]; then
        [ -n "$maximum" ] && checked "$3" "$maximum" && checked "$2" "$minimum"
        exit $?
    fi
    if [ -n "$minimum" ]; then checked "$2" "$minimum" || exit 1; fi
    if [ -n "$maximum" ]; then checked "$3" "$maximum" || exit 1; fi
)

apply_limits() (
    file=$1
    cpct=$2
    gpct=$3
    gov=$4
    floor_policy=$5
    for policy in "$CPU"/policy*; do
        [ -d "$policy" ] || continue
        apply_frequency_limits "$file" "$policy/scaling_min_freq" "$policy/scaling_max_freq" \
            "$policy/scaling_available_frequencies" "$cpct" "$floor_policy" || exit 1
        target=$(recorded_limit "$file" "$policy/scaling_governor")
        [ -n "$target" ] || continue
        if [ "$gov" != keep ] && contains "$(read_value "$policy/scaling_available_governors")" "$gov"; then
            target=$gov
        fi
        checked "$policy/scaling_governor" "$target" || exit 1
    done
    apply_frequency_limits "$file" "$GPU/gpu_min_clock" "$GPU/gpu_max_clock" \
        "$GPU/gpu_freq_table" "$gpct" "$floor_policy"
)

preset() {
    floor_policy=baseline
    case "$2" in
        balanced) cpu=100; gpu=100; governor=keep ;;
        eco) cpu=75; gpu=72; governor=keep ;;
        deep) cpu=55; gpu=55; governor=schedutil; floor_policy=lowest ;;
        fast) cpu=100; gpu=100; governor=schedutil ;;
        max) cpu=100; gpu=100; governor=performance ;;
        *) return 1 ;;
    esac
    apply_limits "$1" "$cpu" "$gpu" "$governor" "$floor_policy"
}

remember() {
    if [ -s "$journal" ]; then
        if ! cp "$journal" "$STORE/undo-next" ||
            ! printf '%s\n' "$(read_value "$STORE/profile")" > "$STORE/undo-profile" ||
            ! mv "$STORE/undo-next" "$STORE/undo"; then
            abort_change 'Could not save the undo record.'
        fi
    fi
    printf '%s\n' "$1" > "$STORE/profile" || abort_change 'Could not save the active profile.'
}

if [ "$1" = status ]; then report; exit 0; fi
[ "$USER_ID" = 0 ] || fail 'Root permission is required.'
if [ "$1" = authorize ]; then echo AUTHORIZED; exit 0; fi
[ "$BOARD" = gts9fepwifi ] || fail 'Tuning requires an SM-X610 tablet.'
if [ -e "$ABI/abi_version" ] && [ "$(read_value "$ABI/abi_version")" != 1 ]; then
    fail 'This kernel interface version is unsupported.'
fi

umask 077
mkdir -p "$STORE" || fail 'Could not create the settings directory.'
if [ "$(read_value "$STORE/boot")" != "$BOOT" ]; then
    rm -f "$STORE/base" "$STORE/session" "$STORE/session-base" "$STORE/undo" "$STORE/undo-profile" "$STORE/profile"
    printf '%s\n' "$BOOT" > "$STORE/boot" || fail 'Could not save the boot session.'
fi
session=$(read_value "$STORE/session")
if [ "$1" = startup-profile ] && { [ -n "$session" ] || [ -s "$STORE/profile" ]; }; then
    echo STARTUP_SKIPPED
    exit 0
fi
case "$1" in
    session-*) ;;
    *) [ -z "$session" ] || fail 'Stop the active automatic session first.' ;;
esac

begin
case "$1" in
    profile|startup-profile)
        capture_limits "$STORE/base" upgrade || fail 'Could not read the initial CPU and GPU settings.'
        preset "$STORE/base" "$2" || abort_change 'Could not verify the profile.'
        remember "$2" ;;
    cpu-max|cpu-min|governor)
        case "$2" in policy[0-9]|policy[0-9][0-9]) ;; *) fail 'Invalid CPU policy.' ;; esac
        base="$CPU/$2"
        capture_limits "$STORE/base" upgrade || fail 'Could not read the initial CPU and GPU settings.'
        if [ "$1" = cpu-max ] || [ "$1" = cpu-min ]; then
            contains "$(read_value "$base/scaling_available_frequencies")" "$3" || fail 'Frequency is not available on this policy.'
            if [ "$1" = cpu-max ]; then
                [ "$3" -ge "$(read_value "$base/scaling_min_freq")" ] || fail 'Frequency is below the current minimum.'
                node="$base/scaling_max_freq"
            else
                [ "$3" -le "$(read_value "$base/scaling_max_freq")" ] || fail 'Frequency is above the current maximum.'
                node="$base/scaling_min_freq"
            fi
            checked "$node" "$3" || abort_change 'CPU frequency could not be verified.'
        else
            contains "$(read_value "$base/scaling_available_governors")" "$3" || fail 'Governor is not available on this policy.'
            checked "$base/scaling_governor" "$3" || abort_change 'Could not verify the CPU governor.'
        fi
        remember custom ;;
    gpu-max|gpu-min)
        contains "$(read_value "$GPU/gpu_freq_table")" "$2" || fail 'Frequency is not available on this GPU.'
        if [ "$1" = gpu-max ]; then
            [ "$2" -ge "$(read_value "$GPU/gpu_min_clock")" ] || fail 'Frequency is below the current minimum.'
            node="$GPU/gpu_max_clock"
        else
            [ "$2" -le "$(read_value "$GPU/gpu_max_clock")" ] || fail 'Frequency is above the current maximum.'
            node="$GPU/gpu_min_clock"
        fi
        capture_limits "$STORE/base" upgrade || fail 'Could not read the initial CPU and GPU settings.'
        checked "$node" "$2" || abort_change 'GPU frequency could not be verified.'
        remember custom ;;
    gpu-governor)
        contains "$(read_value "$GPU/gpu_available_governor")" "$2" || fail 'Governor is unavailable.'
        checked "$GPU/gpu_governor" "$2" || abort_change 'GPU governor could not be verified.'
        remember custom ;;
    io)
        contains "$(printf '%s' "$(read_value "$IO")" | tr -d '[]')" "$2" || fail 'I/O scheduler is unavailable.'
        checked "$IO" "$2" || abort_change 'Could not verify the I/O scheduler.'
        remember "$(read_value "$STORE/profile")" ;;
    vm)
        case "$2" in ''|*[!0-9]*) fail 'Invalid swappiness value.' ;; esac
        [ "$2" -le 200 ] || fail 'Swappiness must be from 0 to 200.'
        checked "$VM" "$2" || abort_change 'Could not verify swappiness.'
        remember "$(read_value "$STORE/profile")" ;;
    tcp)
        contains "$(read_value "$TCP_LIST")" "$2" || fail 'TCP congestion algorithm is unavailable.'
        checked "$TCP" "$2" || abort_change 'Could not verify the TCP congestion algorithm.'
        remember "$(read_value "$STORE/profile")" ;;
    display)
        case "$2" in default|30.0|60.0|90.0) ;; *) fail 'Invalid display mode.' ;; esac
        checked display "$2" || abort_change 'Could not verify the display preference.'
        remember "$(read_value "$STORE/profile")" ;;
    tune)
        node=$(setting_path "$2")
        [ -n "$node" ] || fail 'Unknown tuning control.'
        if [ "$2" = cache-pressure ]; then
            case "$3" in ''|*[!0-9]*) fail 'Invalid cache pressure.' ;; esac
            [ "$3" -ge 1 ] && [ "$3" -le 300 ] || fail 'Cache pressure must be from 1 to 300.'
        else
            contains "$(setting_choices "$2")" "$3" || fail 'Value is outside the supported choices.'
        fi
        if [ "$2" = ram-plus ]; then
            [ "$BOARD" = gts9fepwifi ] || fail 'This RAM Plus control requires the SM-X610.'
            [ "$(settings get global zram_enabled)" = 1 ] || fail 'Enable Samsung RAM Plus first.'
            checked "$RAM_PLUS_PROP" "$3" || abort_change 'The RAM Plus boot size could not be verified.'
        fi
        checked "$node" "$3" || abort_change 'The setting could not be verified.'
        if [ "$2" = ram-plus ] && [ "$(value_at "$RAM_PLUS_PROP")" != "$3" ]; then
            abort_change 'Samsung did not keep the requested RAM Plus boot size.'
        fi
        remember "$(read_value "$STORE/profile")" ;;
    undo)
        [ -s "$STORE/undo" ] || fail 'No change is available to undo.'
        awk '{line[NR]=$0} END {for(i=NR;i>0;i--) print line[i]}' "$STORE/undo" |
            while IFS='|' read -r node before after; do
                actual=$(value_at "$node")
                [ "$actual" = "$before" ] && continue
                [ "$actual" = "$after" ] || exit 1
                checked "$node" "$before" || exit 1
            done || abort_change 'A value changed externally or undo could not be verified.'
        cat "$STORE/undo-profile" > "$STORE/profile" || abort_change 'Could not restore the previous profile label.'
        rm -f "$STORE/undo" "$STORE/undo-profile" ;;
    session-start)
        case "$2" in apps|thermal) ;; *) fail 'Invalid session type.' ;; esac
        [ -z "$session" ] || fail 'An automatic session is already active.'
        capture_limits "$STORE/session-base" || fail 'Could not read settings before the session.'
        printf '%s\n' "$2" > "$STORE/session" || fail 'Could not save the session.' ;;
    session-profile)
        [ "$session" = apps ] && [ -s "$STORE/session-base" ] || fail 'The per-app session is not active.'
        capture_limits "$STORE/session-base" upgrade || fail 'Could not read the session baseline.'
        preset "$STORE/session-base" "$2" || abort_change 'Could not verify the per-app profile.' ;;
    session-cap)
        [ "$session" = thermal ] && [ -s "$STORE/session-base" ] || fail 'The thermal session is not active.'
        case "$2" in ''|*[!0-9]*) fail 'Invalid thermal ceiling.' ;; esac
        [ "$2" -ge 50 ] && [ "$2" -le 100 ] || fail 'Thermal ceiling must be from 50% to 100%.'
        capture_limits "$STORE/session-base" upgrade || fail 'Could not read the session baseline.'
        apply_limits "$STORE/session-base" "$2" "$2" keep baseline || abort_change 'Could not verify the thermal ceiling.' ;;
    session-restore)
        if [ -n "$session" ]; then
            [ -s "$STORE/session-base" ] || fail 'The session baseline is unavailable.'
            capture_limits "$STORE/session-base" upgrade || fail 'Could not read the session baseline.'
            apply_limits "$STORE/session-base" 100 100 keep baseline || abort_change 'Could not restore the settings from before the session.'
            rm -f "$STORE/session" "$STORE/session-base"
        fi ;;
    *) fail 'Unknown command.' ;;
esac
echo VERIFIED
