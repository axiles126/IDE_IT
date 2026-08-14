"""
Виконання коду користувача всередині застосунку.

Викликається з Kotlin: runner.run(code) -> текст виводу.
Увесь друк і помилки перехоплюються, щоб показати їх у панелі «Вивід».
"""

import io
import sys
import traceback


def run(code: str) -> str:
    buffer = io.StringIO()
    old_stdout, old_stderr = sys.stdout, sys.stderr
    sys.stdout = sys.stderr = buffer

    # окремий простір імен: змінні одного запуску не течуть у наступний
    namespace = {"__name__": "__main__", "__doc__": None}

    try:
        compiled = compile(code, "<файл>", "exec")
        exec(compiled, namespace)
    except SystemExit:
        pass                                   # sys.exit() — звичайне завершення
    except SyntaxError as e:
        print(f"Синтаксична помилка, рядок {e.lineno}: {e.msg}")
        if e.text:
            print("   " + e.text.rstrip())
            if e.offset:
                print("   " + " " * max(0, e.offset - 1) + "^")
    except BaseException:
        traceback.print_exc()
    finally:
        sys.stdout, sys.stderr = old_stdout, old_stderr

    return buffer.getvalue()


def version() -> str:
    return sys.version.split()[0]
