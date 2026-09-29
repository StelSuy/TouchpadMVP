"""
Touchpad MVP — сервер для ПК (Windows, без внешних зависимостей).

Протокол: текстовые строки по TCP, каждая заканчивается \\n.

    HELLO|<пароль>            первая строка; ответ: OK|<имя ПК>  или  AUTH_FAIL
    MOVE|dx|dy                относительное движение курсора (пиксели, дробные)
    CLICK|LEFT|RIGHT|MIDDLE   клик
    DOWN|LEFT / UP|LEFT       зажать / отпустить кнопку (перетаскивание, выделение)
    SCROLL|h|v                прокрутка в «щелчках» колеса (v>0 — вверх, h>0 — вправо)
    ZOOM|n                    масштаб: Ctrl + колесо, n щелчков (n>0 — приблизить)
    HOTKEY|CTRL+SHIFT+T       комбинация клавиш
    TEXT|<urlencoded>         ввод текста (любые символы, в т.ч. кириллица)

Запуск:   python server.py [--port 5000] [--password СЕКРЕТ]
Также отвечает на UDP-запрос TOUCHPAD_DISCOVER (кнопка «Найти ПК» в приложении).
"""
import argparse
import socket
import sys
import threading
import time
from urllib.parse import unquote_plus

IS_WINDOWS = sys.platform == "win32"

# ----------------------------------------------------------------- клавиши

VK = {
    "CTRL": 0x11, "ALT": 0x12, "SHIFT": 0x10, "WIN": 0x5B,
    "TAB": 0x09, "ESC": 0x1B, "ENTER": 0x0D, "BACKSPACE": 0x08, "DELETE": 0x2E, "DEL": 0x2E,
    "SPACE": 0x20, "LEFT": 0x25, "UP": 0x26, "RIGHT": 0x27, "DOWN": 0x28,
    "HOME": 0x24, "END": 0x23, "PGUP": 0x21, "PGDN": 0x22, "INSERT": 0x2D,
    "PLUS": 0xBB, "MINUS": 0xBD,
    "VOL_MUTE": 0xAD, "VOL_DOWN": 0xAE, "VOL_UP": 0xAF,
    "MEDIA_NEXT": 0xB0, "MEDIA_PREV": 0xB1, "MEDIA_STOP": 0xB2, "MEDIA_PLAY_PAUSE": 0xB3,
}
for _i in range(1, 13):
    VK["F%d" % _i] = 0x70 + _i - 1
for _c in "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789":
    VK[_c] = ord(_c)

# Клавиши, которым нужен флаг EXTENDEDKEY (иначе Windows принимает их за клавиши цифрового блока)
EXTENDED = {0x25, 0x26, 0x27, 0x28, 0x24, 0x23, 0x21, 0x22, 0x2D, 0x2E, 0x5B}


def parse_hotkey(spec):
    """'CTRL+SHIFT+T' -> [0x11, 0x10, 0x54]. Неизвестное имя -> None."""
    codes = []
    for part in spec.upper().split("+"):
        part = part.strip()
        if part not in VK:
            return None
        codes.append(VK[part])
    return codes or None


# ----------------------------------------------------------------- бэкенд ввода

if IS_WINDOWS:
    import ctypes
    from ctypes import wintypes

    INPUT_MOUSE, INPUT_KEYBOARD = 0, 1
    MOUSEEVENTF_MOVE = 0x0001
    MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP = 0x0002, 0x0004
    MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP = 0x0008, 0x0010
    MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP = 0x0020, 0x0040
    MOUSEEVENTF_WHEEL, MOUSEEVENTF_HWHEEL = 0x0800, 0x1000
    KEYEVENTF_EXTENDEDKEY, KEYEVENTF_KEYUP, KEYEVENTF_UNICODE = 0x0001, 0x0002, 0x0004

    ULONG_PTR = ctypes.c_size_t

    class MOUSEINPUT(ctypes.Structure):
        _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG), ("mouseData", wintypes.DWORD),
                    ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]

    class KEYBDINPUT(ctypes.Structure):
        _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD), ("dwFlags", wintypes.DWORD),
                    ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]

    class _U(ctypes.Union):
        _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT)]

    class INPUT(ctypes.Structure):
        _fields_ = [("type", wintypes.DWORD), ("u", _U)]

    _user32 = ctypes.windll.user32
    _user32.SendInput.argtypes = (wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int)

    def _send(inp):
        _user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(INPUT))

    def _mouse(flags, dx=0, dy=0, data=0):
        inp = INPUT(type=INPUT_MOUSE)
        inp.u.mi = MOUSEINPUT(dx, dy, data & 0xFFFFFFFF, flags, 0, 0)
        _send(inp)

    def _key(vk=0, scan=0, flags=0):
        inp = INPUT(type=INPUT_KEYBOARD)
        inp.u.ki = KEYBDINPUT(vk, scan, flags, 0, 0)
        _send(inp)

    _BTN = {"LEFT": (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP),
            "RIGHT": (MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP),
            "MIDDLE": (MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP)}

    def move(dx, dy): _mouse(MOUSEEVENTF_MOVE, dx, dy)
    def button(name, down): _mouse(_BTN[name][0 if down else 1])
    def wheel(units): _mouse(MOUSEEVENTF_WHEEL, data=units)
    def hwheel(units): _mouse(MOUSEEVENTF_HWHEEL, data=units)

    def key_event(vk, down):
        flags = (KEYEVENTF_EXTENDEDKEY if vk in EXTENDED else 0) | (0 if down else KEYEVENTF_KEYUP)
        _key(vk=vk, flags=flags)

    def type_text(text):
        data = text.encode("utf-16-le")           # суррогатные пары уходят как две единицы — так и нужно
        for i in range(0, len(data), 2):
            unit = data[i] | (data[i + 1] << 8)
            _key(scan=unit, flags=KEYEVENTF_UNICODE)
            _key(scan=unit, flags=KEYEVENTF_UNICODE | KEYEVENTF_KEYUP)
else:
    # Не Windows: только лог — удобно проверять протокол
    def move(dx, dy): print("[dry-run] move", dx, dy)
    def button(name, down): print("[dry-run] button", name, "down" if down else "up")
    def wheel(units): print("[dry-run] wheel", units)
    def hwheel(units): print("[dry-run] hwheel", units)
    def key_event(vk, down): print("[dry-run] key", hex(vk), "down" if down else "up")
    def type_text(text): print("[dry-run] type", repr(text))


def press_hotkey(codes):
    for vk in codes:
        key_event(vk, True)
    for vk in reversed(codes):
        key_event(vk, False)


# ----------------------------------------------------------------- обработчик соединения

class Session:
    """Состояние одного подключённого телефона."""

    def __init__(self):
        self.carry = [0.0, 0.0]          # дробные остатки движения курсора
        self.wheel_acc = [0.0, 0.0]      # накопление прокрутки (h, v) в единицах WHEEL_DELTA (120 = 1 щелчок)
        self.held = set()                # зажатые кнопки мыши — отпустим при обрыве

    def handle(self, line):
        parts = line.strip().split("|")
        cmd = parts[0].upper() if parts else ""
        try:
            if cmd == "MOVE" and len(parts) >= 3:
                self.carry[0] += float(parts[1].replace(",", "."))
                self.carry[1] += float(parts[2].replace(",", "."))
                ix, iy = int(self.carry[0]), int(self.carry[1])
                self.carry[0] -= ix
                self.carry[1] -= iy
                if ix or iy:
                    move(ix, iy)

            elif cmd == "CLICK" and len(parts) >= 2 and parts[1].upper() in ("LEFT", "RIGHT", "MIDDLE"):
                name = parts[1].upper()
                button(name, True)
                button(name, False)

            elif cmd in ("DOWN", "UP") and len(parts) >= 2 and parts[1].upper() in ("LEFT", "RIGHT", "MIDDLE"):
                name = parts[1].upper()
                down = cmd == "DOWN"
                button(name, down)
                (self.held.add if down else self.held.discard)(name)

            elif cmd == "SCROLL" and len(parts) >= 3:
                self.wheel_acc[0] += float(parts[1]) * 120
                self.wheel_acc[1] += float(parts[2]) * 120
                # отправляем, когда накопилось хотя бы 1/6 щелчка — плавно, но без мусора из мелких значений
                if abs(self.wheel_acc[1]) >= 20:
                    n = int(self.wheel_acc[1]); self.wheel_acc[1] -= n; wheel(n)
                if abs(self.wheel_acc[0]) >= 20:
                    n = int(self.wheel_acc[0]); self.wheel_acc[0] -= n; hwheel(n)

            elif cmd == "ZOOM" and len(parts) >= 2:
                steps = int(float(parts[1]))
                if steps:
                    key_event(VK["CTRL"], True)
                    wheel(steps * 120)
                    key_event(VK["CTRL"], False)

            elif cmd == "HOTKEY" and len(parts) >= 2:
                codes = parse_hotkey(parts[1])
                if codes:
                    press_hotkey(codes)

            elif cmd == "TEXT" and len(parts) >= 2:
                type_text(unquote_plus(parts[1]))
        except (ValueError, KeyError):
            pass    # битая команда — пропускаем, соединение не рвём

    def release_all(self):
        for name in list(self.held):
            button(name, False)
        self.held.clear()


def serve_client(conn, addr, password):
    name = socket.gethostname()
    session = Session()
    try:
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        conn.settimeout(5)                          # на рукопожатие даём 5 секунд
        buf = b""
        authed = False
        while True:
            data = conn.recv(4096)
            if not data:
                break
            buf += data
            while b"\n" in buf:
                raw, buf = buf.split(b"\n", 1)
                line = raw.decode("utf-8", "ignore")
                if not authed:
                    parts = line.strip().split("|", 1)
                    given = unquote_plus(parts[1]) if len(parts) > 1 else ""
                    if parts[0] != "HELLO" or (password and given != password):
                        conn.sendall(b"AUTH_FAIL\n")
                        print(f"[{addr[0]}] отклонён (неверный пароль)")
                        return
                    conn.sendall(("OK|%s\n" % name).encode("utf-8"))
                    conn.settimeout(None)
                    authed = True
                    print(f"[{addr[0]}] подключился")
                else:
                    session.handle(line)
    except OSError:
        pass
    finally:
        session.release_all()
        try:
            conn.close()
        except OSError:
            pass
        print(f"[{addr[0]}] отключился")


def discovery_responder(port):
    """Отвечает на широковещательный запрос приложения «Найти ПК»."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("0.0.0.0", port))
    except OSError as e:
        print("Автопоиск недоступен:", e)
        return
    reply = ("TOUCHPAD_HERE|%s" % socket.gethostname()).encode("utf-8")
    while True:
        try:
            data, addr = s.recvfrom(256)
            if data.startswith(b"TOUCHPAD_DISCOVER"):
                s.sendto(reply, addr)
        except OSError:
            break


def local_ips():
    ips = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))       # пакеты не уходят, нужно только выбрать сетевой интерфейс
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    return sorted(i for i in ips if not i.startswith("127."))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=5000)
    ap.add_argument("--password", default="", help="пароль (тот же — в настройках приложения)")
    args = ap.parse_args()

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", args.port))
    srv.listen(5)
    threading.Thread(target=discovery_responder, args=(args.port,), daemon=True).start()

    print("Touchpad сервер запущен. Пароль:", "включён" if args.password else "нет")
    print("Введи в приложении один из адресов или нажми «Найти ПК»:")
    for ip in local_ips():
        print(f"   {ip}:{args.port}")
    print("Ctrl+C — выход\n")

    try:
        while True:
            conn, addr = srv.accept()
            threading.Thread(target=serve_client, args=(conn, addr, args.password), daemon=True).start()
    except KeyboardInterrupt:
        print("\nОстановлено")


if __name__ == "__main__":
    main()
