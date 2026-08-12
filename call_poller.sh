#!/data/data/com.termux/files/usr/bin/sh
# Placing calls with NO usb debugging: the phone polls your server and dials.
# Outbound HTTPS only -- nothing listens on the phone, nothing is exposed.
#
# On the phone (F-Droid versions, NOT Play Store -- those are abandoned):
#   1. Install Termux and Termux:API
#   2. pkg install termux-api curl
#   3. Open Termux:API once and grant the Phone permission
#   4. termux-wake-lock                 # keep polling while screen is off
#   5. Settings > Apps > Termux > Battery > Unrestricted
#   6. sh call_poller.sh
#
# Your server must serve QUEUE_URL returning either an empty body (nothing to
# do) or one phone number as plain text. Consuming it is the server's job --
# return each number once, or this will redial it every 10 seconds.

QUEUE_URL="${QUEUE_URL:-https://calls.yourdomain.com/next}"
QUEUE_TOKEN="${QUEUE_TOKEN:?set QUEUE_TOKEN to the same value as the VPS}"
TALK_SECONDS="${TALK_SECONDS:-45}"

# ponytail: no hangup. termux-telephony-call only dials -- Termux has no API to
# end a call, and `input keyevent KEYCODE_ENDCALL` needs INJECT_EVENTS, which
# normal apps cannot hold. So the call ends when the other side hangs up or the
# carrier times it out. TALK_SECONDS only paces the loop, it does not cut the
# call. On a rooted phone, uncomment the su line to actually hang up.
hangup() {
    : # su -c "input keyevent 6"
}

while true; do
    # --max-time 40 > the server's 25s long-poll hold, so the request parks on
    # the server until a number arrives instead of returning empty immediately.
    number=$(curl -fsS --max-time 40 -H "Authorization: Bearer $QUEUE_TOKEN" \
                  "$QUEUE_URL" | tr -d '[:space:]')
    case "$number" in
        "")             ;;                      # nothing queued
        *[!0-9+]*)      echo "ignoring junk: $number" ;;
        *)              echo "calling $number"
                        termux-telephony-call "$number"
                        sleep "$TALK_SECONDS"
                        hangup
                        continue                # straight back for the next one
                        ;;
    esac
    sleep 2   # only reached on an empty/failed poll; the server holds the rest
done
