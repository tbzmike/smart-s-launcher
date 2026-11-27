"""Real task IDs and Android framework state; no simulated task objects or skipped assertions."""
import json
import re
import shlex
import subprocess
import time


def shell(command):
    return subprocess.check_output(["adb", "shell", command], text=True, timeout=35).strip()


assert shell("id -u") == "0", "CI emulator must support a root shell for native binder verification"
apk = next(line.split(":", 1)[1] for line in shell("pm path com.tbzmike.smartslauncher.debug").splitlines()
           if line.endswith("/base.apk"))
prefix = "SMART_S_FREEFORM:"
base = ("CLASSPATH=" + shlex.quote(apk) + " /system/bin/app_process /system/bin "
        "fr.neamar.kiss.freeform.NativeTaskBridge ")
user = int(shell("am get-current-user"))
first_pkg = "com.tbzmike.shortcutfixture.target"
second_pkg = "com.tbzmike.shortcutfixture.icebox"


def bridge(action, task=None, bounds=(0, 0, 0, 0), expect=True):
    command = base + action + " " + str(user)
    if task:
        command += " " + str(task["id"]) + " " + shlex.quote(task["component"]) + " " + " ".join(map(str, bounds))
    output = shell(command + " 2>&1")
    rows = [line[len(prefix):] for line in output.splitlines() if line.startswith(prefix)]
    assert len(rows) == 1, (command, output)
    result = json.loads(rows[0])
    assert result.get("ok") is expect, (action, result, output)
    return result


def tasks():
    return bridge("list")["tasks"]


def task_for(rows, pkg):
    matches = [row for row in rows if row["pkg"] == pkg and row["type"] == 1]
    assert len(matches) <= 1, ("duplicate tasks", pkg, matches)
    return matches[0] if matches else None


def rect(task):
    return tuple(task[key] for key in ("l", "t", "r", "b"))


def wait_for(description, predicate, attempts=12):
    latest = None
    for _ in range(attempts):
        latest = tasks()
        if predicate(latest):
            print("PASS:", description, flush=True)
            return latest
        time.sleep(0.35)
    raise AssertionError((description, latest))


# The list command must already work before launching any task.
tasks()
for pkg in (first_pkg, second_pkg):
    shell("am force-stop " + pkg)
    output = shell("am start -W --windowingMode 5 -n " + pkg + "/com.tbzmike.shortcutfixture.FixtureFreeformActivity")
    assert "Error" not in output, output


rows = wait_for("two real independent native freeform tasks", lambda rows:
                all(task_for(rows, pkg) and task_for(rows, pkg)["mode"] == 5
                    for pkg in (first_pkg, second_pkg)))
first = task_for(rows, first_pkg)
second = task_for(rows, second_pkg)
assert first["id"] != second["id"]
first_id, second_id = first["id"], second["id"]
second_rect = rect(second)
old = rect(first)
screen_w, screen_h = map(int, re.findall(r"(\d+)x(\d+)", shell("wm size"))[-1])
# Move the first task, retaining its size where possible. Actual bounds are read back from Android.
dx = min(80, max(0, screen_w - old[2] - 10))
if dx == 0:
    dx = -min(80, max(0, old[0] - 10))
dy = min(80, max(0, screen_h - old[3] - 80))
if dy == 0:
    dy = -min(80, max(0, old[1] - 80))
requested = (old[0] + dx, old[1] + dy, old[2] + dx, old[3] + dy)
if requested == old:
    requested = (old[0] + 20, old[1] + 20, old[2] - 20, old[3] - 20)
bridge("resize", first, requested)
rows = wait_for("resize changes only the selected existing task", lambda rows:
                task_for(rows, first_pkg) and task_for(rows, first_pkg)["id"] == first_id
                and task_for(rows, first_pkg)["visible"] and rect(task_for(rows, first_pkg)) != old
                and task_for(rows, second_pkg) and task_for(rows, second_pkg)["id"] == second_id
                and rect(task_for(rows, second_pkg)) == second_rect)
first = task_for(rows, first_pkg)
saved = rect(first)
bridge("minimize", first)
rows = wait_for("minimize hides same task without removing either task", lambda rows:
                task_for(rows, first_pkg) and task_for(rows, first_pkg)["id"] == first_id
                and task_for(rows, first_pkg)["mode"] == 5 and not task_for(rows, first_pkg)["visible"]
                and task_for(rows, second_pkg) and task_for(rows, second_pkg)["id"] == second_id)
bridge("restore", task_for(rows, first_pkg), saved)
rows = wait_for("restore returns same task and previous popup bounds", lambda rows:
                task_for(rows, first_pkg) and task_for(rows, first_pkg)["id"] == first_id
                and task_for(rows, first_pkg)["mode"] == 5 and task_for(rows, first_pkg)["visible"]
                and rect(task_for(rows, first_pkg)) == saved)
bridge("maximize", task_for(rows, first_pkg))
rows = wait_for("maximize changes same task to fullscreen, preserving second task", lambda rows:
                task_for(rows, first_pkg) and task_for(rows, first_pkg)["id"] == first_id
                and task_for(rows, first_pkg)["mode"] == 1 and task_for(rows, first_pkg)["visible"]
                and task_for(rows, second_pkg) and task_for(rows, second_pkg)["id"] == second_id)
bridge("restore", task_for(rows, first_pkg), saved)
rows = wait_for("fullscreen restores original popup without relaunch", lambda rows:
                task_for(rows, first_pkg) and task_for(rows, first_pkg)["id"] == first_id
                and task_for(rows, first_pkg)["mode"] == 5 and task_for(rows, first_pkg)["visible"]
                and rect(task_for(rows, first_pkg)) == saved)
wrong = dict(task_for(rows, first_pkg))
wrong["component"] = task_for(rows, second_pkg)["component"]
bridge("close", wrong, expect=False)
assert task_for(tasks(), first_pkg)["id"] == first_id
bridge("close", task_for(rows, first_pkg))
rows = wait_for("close removes only the selected task", lambda rows:
                task_for(rows, first_pkg) is None and task_for(rows, second_pkg)
                and task_for(rows, second_pkg)["id"] == second_id)
bridge("close", task_for(rows, second_pkg))
wait_for("both explicitly closed task IDs are gone", lambda rows:
         task_for(rows, first_pkg) is None and task_for(rows, second_pkg) is None)
print("PASS: real native multi-task controls, exact task identity, minimize/restore, resize, maximize and close", flush=True)
