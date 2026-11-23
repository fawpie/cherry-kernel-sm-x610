#!/system/bin/sh
# Run once via the selected root manager, after Android's package service is ready.
directory=/data/adb/cherry-manager-install
apk="$directory/Cherry-Manager.apk"
hook=/data/adb/service.d/99-cherry-manager-install.sh

cleanup() {
    rm -f "$hook" "$apk"
    rmdir "$directory"
}
trap cleanup EXIT
[ -s "$apk" ] || exit 1

tries=0
while [ "$(getprop sys.boot_completed)" != 1 ]; do
    [ "$tries" -lt 90 ] || exit 1
    sleep 2
    tries=$((tries + 1))
done

size=$(wc -c < "$apk") || exit 1
result=$(cat "$apk" | /system/bin/cmd package install -r -S "$size" 2>&1)
code=$?
if [ "$code" = 0 ]; then
    /system/bin/log -t CherryInstaller "Cherry Manager installed."
else
    /system/bin/log -p e -t CherryInstaller "Cherry Manager could not be installed: $result"
fi
exit "$code"
