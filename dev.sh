#!/bin/bash
# 快速开发循环：一条命令跑完 构建 → 部署 → 重启 → 截图
#
# 用法：
#   ./dev.sh            只改 Java/资源 → 全流程（构建+安装+同步+重启SystemUI+截图）
#   ./dev.sh ui         只改 MainActivity/UI → 安装即可，不重启 SystemUI
#   ./dev.sh hook       只改 hook 且不重新构建 → 直接同步现有 APK + 重启
#   ./dev.sh shot       只看效果 → 截图并裁剪状态栏区域
#   ./dev.sh log        抓最近的 hook 日志
#   ./dev.sh grep XXX   在 hook 日志里搜关键字
#
# 关键提速点（实测）：
#   - 构建 2.7s / 安装 0.6s / 推送 0.5s，都不是瓶颈
#   - SystemUI 新进程 0.5s 起、hook 0.65s 就绪 → 轮询 "installed" 比 sleep 10 快 15 倍
#   - UI 改动不需要重启 SystemUI（hook 只在进程启动时加载，UI 是独立 Activity）
set -euo pipefail
cd "$(dirname "$0")"

DEV="${ADB_SERIAL:-$(adb devices | awk 'NR==2{print $1}')}"
ADB="adb -s $DEV"
HOOKLOG="/tmp/hide5g_hook.log"
SHOTDIR="/tmp/hide5g_shots"
CROP_ICONS="560,0,1272,150"   # 状态栏右侧图标区
CROP_BAR="0,0,1272,130"       # 整条状态栏
WATCH="${WATCH:-icons}"       # icons | bar

mkdir -p "$SHOTDIR"

apk_path() {
  ls -t release/hide5G-*.apk 2>/dev/null | head -1
}

# 后台抓 logcat，脚本结束后自动收
LOG_PID=""
start_log() {
  rm -f "$HOOKLOG"
  $ADB logcat -c >/dev/null 2>&1 || true
  ( $ADB logcat -v brief -s LSPosedFramework > "$HOOKLOG" 2>&1 & echo $! > /tmp/hide5g_logpid )
  LOG_PID=$(cat /tmp/hide5g_logpid)
}
stop_log() {
  if [ -n "$LOG_PID" ]; then
    kill "$LOG_PID" 2>/dev/null || true
  fi
  pkill -f "adb -s $DEV logcat" 2>/dev/null || true
  LOG_PID=""
}

# 轮询等 hook 加载完成，超时 20s
wait_hook() {
  for _ in $(seq 1 100); do
    if grep -q "installed" "$HOOKLOG" 2>/dev/null; then
      return 0
    fi
    sleep 0.2
  done
  echo "!! hook 20s 内未就绪（模块可能没被 LSPosed 加载）"
  return 1
}

# 等状态栏图标真正画出来（避免截到进场动画中间帧）
wait_icons() {
  for _ in $(seq 1 50); do
    if grep -qE "applyAll|refreshIcons|icons loaded" "$HOOKLOG" 2>/dev/null; then
      sleep 0.6
      return 0
    fi
    sleep 0.2
  done
  sleep 1
}

restart_systemui() {
  local old p
  old=$($ADB shell "pidof com.android.systemui" 2>/dev/null | tr -d '\r' || true)
  $ADB shell "su -c 'pkill -f com.android.systemui'" 2>/dev/null || true
  for _ in $(seq 1 100); do
    p=$($ADB shell "pidof com.android.systemui" 2>/dev/null | tr -d '\r' || true)
    if [ -n "$p" ] && [ "$p" != "$old" ]; then
      echo "   SystemUI $old → $p"
      return 0
    fi
    sleep 0.1
  done
  echo "!! SystemUI 未重启成功"
  return 0
}

deploy() {
  local apk
  apk=$(apk_path)
  [ -n "$apk" ] || { echo "!! 没有 APK 产物"; exit 1; }
  $ADB install -r -d "$apk" >/dev/null 2>&1 || { echo "!! 安装失败"; exit 1; }
  # LSPosed 的 apk_path 固定指向这个路径，必须每次同步，否则模块静默不加载
  $ADB push "$apk" /data/local/tmp/h.apk >/dev/null 2>&1 || true
  $ADB shell "su -c 'mkdir -p /data/adb/hide5g && cp /data/local/tmp/h.apk /data/adb/hide5g/base.apk && chmod 644 /data/adb/hide5g/base.apk'"
  echo "   已部署 $(basename "$apk")"
  # install 后包处于 stopped 状态，SystemUI 作为普通应用查不了它的 ContentProvider
  # （表现为 loadIcon failed: No content provider，然后静默退回系统图标）
  $ADB shell "am start -n com.tonyma.hide5g/com.tonyma.hide5g.MainActivity" >/dev/null 2>&1 || true
  sleep 1.2
  $ADB shell "input keyevent KEYCODE_HOME" >/dev/null 2>&1 || true
}

# 唤醒屏幕：DOZE_SUSPEND 时按一次电源只是到 DOZE，要按到状态变 ON 为止
wake() {
  local st
  for _ in 1 2 3 4; do
    st=$($ADB shell "dumpsys display 2>/dev/null | grep -m1 mScreenState=" 2>/dev/null | tr -d '\r' || true)
    case "$st" in
      *"=ON"*)
        $ADB shell "input swipe 636 2300 636 700 100" >/dev/null 2>&1 || true
        sleep 0.7
        return 0
        ;;
    esac
    $ADB shell "input keyevent 26" >/dev/null 2>&1 || true
    sleep 1.1
  done
  return 0
}

shot() {
  local ts crop out avg
  wake
  ts=$(date +%H%M%S)
  $ADB shell "screencap -p /sdcard/_d.png" >/dev/null 2>&1
  $ADB pull /sdcard/_d.png "$SHOTDIR/raw_$ts.png" >/dev/null 2>&1 || true
  # 息屏（DOZE）时整屏全黑，唤醒后重截一次
  avg=$(python3 - "$SHOTDIR/raw_$ts.png" <<'PY'
import sys
from PIL import Image, ImageStat
print(f"{ImageStat.Stat(Image.open(sys.argv[1]).convert('L')).mean[0]:.0f}")
PY
)
  if [ "${avg:-0}" -lt 10 ]; then
    echo "   屏幕是黑的（亮度 ${avg}），尝试唤醒…"
    wake
    $ADB shell "screencap -p /sdcard/_d.png" >/dev/null 2>&1
    $ADB pull /sdcard/_d.png "$SHOTDIR/raw_$ts.png" >/dev/null 2>&1 || true
  fi
  if [ "$WATCH" = "bar" ]; then crop="$CROP_BAR"; else crop="$CROP_ICONS"; fi
  out="$SHOTDIR/${WATCH}_$ts.png"
  python3 - "$SHOTDIR/raw_$ts.png" "$out" "$crop" <<'PY'
import sys
from PIL import Image, ImageStat
src, dst, crop = sys.argv[1], sys.argv[2], sys.argv[3]
x1, y1, x2, y2 = (int(v) for v in crop.split(","))
im = Image.open(src).convert("RGB").crop((x1, y1, x2, y2))
im.resize((im.width * 3, im.height * 3), Image.LANCZOS).save(dst)
# 报一下整屏平均亮度，防止截到熄屏/锁屏
avg = ImageStat.Stat(Image.open(src).convert("L")).mean[0]
flag = "  !! 画面很暗，可能熄屏了" if avg < 12 else ""
print(f"   截图 {dst}  (整屏亮度 {avg:.0f}){flag}")
PY
  echo "$out"
}

show_log() {
  if [ ! -f "$HOOKLOG" ]; then
    echo "（没有日志，先跑 ./dev.sh）"
    return
  fi
  grep -oE "hide5G: [^<]*" "$HOOKLOG" | head -40
}

case "${1:-all}" in
  all)
    echo "▶ 构建"
    if ! ./build.sh > /tmp/hide5g_build.log 2>&1; then
      echo "!! 构建失败"; grep -v "bootstrap class path" /tmp/hide5g_build.log | tail -25; exit 1
    fi
    grep -E "完成:" /tmp/hide5g_build.log || true
    start_log
    trap stop_log EXIT
    echo "▶ 部署"
    deploy
    echo "▶ 重启 SystemUI"
    restart_systemui
    wait_hook
    wait_icons
    echo "▶ 截图"
    shot
    echo "▶ hook 日志"
    show_log | head -12
    ;;
  ui)
    echo "▶ 构建"
    if ! ./build.sh > /tmp/hide5g_build.log 2>&1; then
      echo "!! 构建失败"; grep -v "bootstrap class path" /tmp/hide5g_build.log | tail -25; exit 1
    fi
    grep -E "完成:" /tmp/hide5g_build.log || true
    echo "▶ 只安装（UI 改动不重启 SystemUI）"
    deploy
    $ADB shell "am start -n com.tonyma.hide5g/com.tonyma.hide5g.MainActivity" >/dev/null 2>&1
    echo "   已打开 App"
    ;;
  hook)
    start_log
    trap stop_log EXIT
    echo "▶ 同步现有 APK（不重新构建）"
    deploy
    echo "▶ 重启 SystemUI"
    restart_systemui
    wait_hook
    wait_icons
    echo "▶ 截图"
    shot
    show_log | head -12
    ;;
  shot)
    shot
    ;;
  log)
    show_log
    ;;
  grep)
    grep -oE "hide5G: [^<]*" "$HOOKLOG" 2>/dev/null | grep -i "${2:-}" | head -30
    ;;
  *)
    echo "用法: ./dev.sh [all|ui|hook|shot|log|grep <关键字>]"
    exit 1
    ;;
esac
