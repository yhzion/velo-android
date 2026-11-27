#!/bin/sh
# adb to the measured device. ADB_SERIAL picks it (adb -s). A forwarded adb server socket in
# the environment (ADB_SERVER_SOCKET, ANDROID_ADB_SERVER_ADDRESS) is ignored, so the local
# server on its default port answers.
exec env -u ADB_SERVER_SOCKET -u ANDROID_ADB_SERVER_ADDRESS adb ${ADB_SERIAL:+-s "$ADB_SERIAL"} "$@"
