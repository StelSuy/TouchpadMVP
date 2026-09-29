"""
Touchpad MVP — сервер для ПК (Windows, без внешних зависимостей).

Принимает по TCP строки от Android-приложения:
    MOVE|dx|dy
    CLICK|LEFT
    CLICK|RIGHT

Запуск:   python server.py [--port 5000]
"""
import argparse
import socket
import sys

IS_WINDOWS = sys.platform == "win32"

if IS_WINDOWS:
    import ctypes
    from ctypes import wintypes

    INPUT_MOUSE = 0
    MOUSEEVENTF_MOVE = 0x0001
    MOUSEEVENTF_LEFTDOWN = 0x0002
    MOUSEEVENTF_LEFTUP = 0x0004
    MOUSEEVENTF_RIGHTDOWN = 0x0008
    MOUSEEVENTF_RIGHTUP = 0x0010

    class MOUSEINPUT(ctypes.Structure):
        _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG),
                    ("mouseData", wintypes.DWORD), ("dwFlags", wintypes.DWORD),
                    ("time", wintypes.DWORD),
                    ("dwExtraInfo", ctypes.POINTER(ctypes.c_ulong))]

    class _INPUTUNION(ctypes.Union):
        _fields_ = [("mi", MOUSEINPUT)]

    class INPUT(ctypes.Structure):
        _fields_ = [("type", wintypes.DWORD), ("u", _INPUTUNION)]

    def _send(flags, dx=0, dy=0):
        inp = INPUT(type=INPUT_MOUSE)
        inp.u.mi = MOUSEINPUT(dx, dy, 0, flags, 0, None)
        ctypes.windll.user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(INPUT))

    def move(dx, dy):
        _send(MOUSEEVENTF_MOVE, dx, dy)

    def click(button):
        down, up = ((MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP) if button == "LEFT"
                    else (MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP))
        _send(down)
        _send(up)
else:
    # Не Windows: только лог (для проверки протокола)
    def move(dx, dy):
        print(f"[dry-run] move {dx} {dy}")

    def click(button):
        print(f"[dry-run] click {button}")


def handle_line(line, carry):
    """Разбор одной команды. carry — накопленные дробные остатки движения."""
    parts = line.strip().split("|")
    if not parts or not parts[0]:
        return
    cmd = parts[0].upper()
    try:
        if cmd == "MOVE" and len(parts) >= 3:
            # replace(',', '.') — на случай старой версии приложения с русской локалью
            carry[0] += float(parts[1].replace(",", "."))
            carry[1] += float(parts[2].replace(",", "."))
            ix, iy = int(carry[0]), int(carry[1])   # целая часть уходит в систему,
            carry[0] -= ix                          # дробная копится — мелкие сдвиги не теряются
            carry[1] -= iy
            if ix or iy:
                move(ix, iy)
        elif cmd == "CLICK" and len(parts) >= 2 and parts[1].upper() in ("LEFT", "RIGHT"):
            click(parts[1].upper())
    except ValueError:
        pass  # битая строка — пропускаем


def local_ips():
    ips = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))   # пакеты не отправляются, нужно только выбрать интерфейс
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    return sorted(i for i in ips if not i.startswith("127."))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=5000)
    args = ap.parse_args()

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", args.port))
    srv.listen(1)

    print("Touchpad сервер запущен. Введи в приложении один из IP:")
    for ip in local_ips():
        print(f"   {ip}:{args.port}")
    print("Ctrl+C — выход\n")

    try:
        while True:
            conn, addr = srv.accept()
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            print(f"Подключился {addr[0]}")
            carry = [0.0, 0.0]
            buf = b""
            try:
                while True:
                    data = conn.recv(4096)
                    if not data:
                        break
                    buf += data
                    while b"\n" in buf:
                        raw, buf = buf.split(b"\n", 1)
                        handle_line(raw.decode("utf-8", "ignore"), carry)
            except OSError:
                pass
            finally:
                conn.close()
                print("Отключился\n")
    except KeyboardInterrupt:
        print("\nОстановлено")


if __name__ == "__main__":
    main()
