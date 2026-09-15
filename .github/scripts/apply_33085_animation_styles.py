from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]


def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")


def write(rel, text):
    (ROOT / rel).write_text(text, encoding="utf-8")


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def insert_before_none(text, array_name, labels):
    pattern = re.compile(
        rf'(<string-array name="{re.escape(array_name)}">)(.*?)(</string-array>)',
        re.S,
    )
    match = pattern.search(text)
    if not match:
        raise SystemExit(f"Missing array {array_name}")
    body = match.group(2)
    if any(f"<item>{value}</item>" in body for value in labels):
        raise SystemExit(f"{array_name}: one or more new entries already present")
    marker = "        <item>None</item>" if array_name.endswith("_entries") else "        <item>none</item>"
    if body.count(marker) != 1:
        raise SystemExit(f"{array_name}: expected one None marker")
    inserted = "\n".join(f"        <item>{value}</item>" for value in labels) + "\n"
    body = body.replace(marker, inserted + marker, 1)
    return text[:match.start(2)] + body + text[match.end(2):]


# Version bump -----------------------------------------------------------------
build_path = "app/build.gradle"
build = read(build_path)
build = replace_once(
    build,
    '// Smart S Launcher 3.30.84 - distinct notification icon/message interactions\n        versionCode 512\n        versionName "3.30.84"',
    '// Smart S Launcher 3.30.85 - expanded verified animation style library\n        versionCode 513\n        versionName "3.30.85"',
    "version bump",
)
write(build_path, build)

# Settings arrays ---------------------------------------------------------------
arrays_path = "app/src/main/res/values/arrays_smart_features.xml"
arrays = read(arrays_path)
for name, values in (
    ("smart_scroll_animation_entries", ["Parallax", "Swing", "Accordion", "Spiral", "Cube"]),
    ("smart_scroll_animation_values", ["parallax", "swing", "accordion", "spiral", "cube"]),
    ("smart_enter_animation_entries", ["Drop", "Swing", "Fold", "Pop", "Cube"]),
    ("smart_enter_animation_values", ["drop", "swing", "fold", "pop", "cube"]),
    ("smart_exit_animation_entries", ["Drop away", "Swing away", "Fold", "Collapse", "Cube"]),
    ("smart_exit_animation_values", ["drop", "swing", "fold", "collapse", "cube"]),
    ("smart_popup_animation_entries", ["Drop", "Swing", "Fold", "Pop", "Cube"]),
    ("smart_popup_animation_values", ["drop", "swing", "fold", "pop", "cube"]),
    ("smart_switch_animation_entries", ["Parallax", "Swing", "Fold", "Cube", "Reveal"]),
    ("smart_switch_animation_values", ["parallax", "swing", "fold", "cube", "reveal"]),
    ("smart_toast_animation_entries", ["Pop", "Swing", "Drop"]),
    ("smart_toast_animation_values", ["pop", "swing", "drop"]),
):
    arrays = insert_before_none(arrays, name, values)
write(arrays_path, arrays)

# Animation engine --------------------------------------------------------------
engine_path = "app/src/main/java/fr/neamar/kiss/ui/SmartAnimationEngine.java"
engine = read(engine_path)

engine = replace_once(
    engine,
    '    public static void animatePopupViewIn(View view) {\n        animateViewIn(view, "smart-animation-popup-open", "scale");\n    }',
    '''    /** Animate the launcher window whenever Home becomes visible. */
    public static void animateWindowEnter(View view) {
        animateViewIn(view, "smart-animation-window-enter", "fade");
    }

    /**
     * Animate the launcher-owned window out without delaying the target app launch. The animation
     * is intentionally view-only: there is no Handler, executor, polling or background work.
     */
    public static void animateWindowExit(View view) {
        if (view == null) return;
        view.animate().cancel();
        if (!isEnabled(view.getContext())) {
            reset(view);
            return;
        }
        String style = getStyle(view.getContext(), "smart-animation-window-exit", "fade");
        if ("none".equals(style)) {
            reset(view);
            return;
        }
        android.view.ViewPropertyAnimator animator = view.animate()
                .setDuration(Math.max(70L, duration(view.getContext()) * 3 / 4))
                .setInterpolator(new AccelerateDecelerateInterpolator());
        applyExitStyle(view, animator, style);
        animator.setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                view.animate().setListener(null);
                reset(view);
            }
        }).start();
    }

    public static void animatePopupViewIn(View view) {
        animateViewIn(view, "smart-animation-popup-open", "scale");
    }''',
    "window animation methods",
)

engine = replace_once(
    engine,
    '("spring".equals(style) || "elastic".equals(style)\n                        || "bounce".equals(style))',
    '("spring".equals(style) || "elastic".equals(style)\n                        || "bounce".equals(style) || "pop".equals(style)\n                        || "swing".equals(style))',
    "enter interpolator",
)

engine = replace_once(
    engine,
    '''            case "bounce":
                view.setAlpha(0f);
                view.setScaleX(1.12f);
                view.setScaleY(0.72f);
                view.setTranslationY(dp(view, 36));
                break;
            case "scale":''',
    '''            case "bounce":
                view.setAlpha(0f);
                view.setScaleX(1.12f);
                view.setScaleY(0.72f);
                view.setTranslationY(dp(view, 36));
                break;
            case "drop":
                view.setAlpha(0f);
                view.setTranslationY(-dp(view, 76));
                view.setScaleX(0.96f);
                view.setScaleY(0.96f);
                break;
            case "swing":
                view.setAlpha(0f);
                view.setTranslationX(dp(view, 34));
                view.setRotation(13f);
                view.setScaleX(0.92f);
                view.setScaleY(0.92f);
                break;
            case "fold":
                view.setAlpha(0f);
                view.setScaleY(0.18f);
                view.setRotationX(-22f);
                break;
            case "pop":
                view.setAlpha(0f);
                view.setScaleX(0.52f);
                view.setScaleY(0.52f);
                break;
            case "cube":
                view.setAlpha(0f);
                view.setTranslationX(dp(view, 26));
                view.setRotationY(72f);
                view.setScaleX(0.84f);
                view.setScaleY(0.94f);
                break;
            case "scale":''',
    "enter styles",
)

engine = replace_once(
    engine,
    '''            case "bounce":
                animator.alpha(0f).translationY(dp(view, 30)).scaleX(0.86f).scaleY(1.12f);
                break;
            case "shrink":''',
    '''            case "bounce":
                animator.alpha(0f).translationY(dp(view, 30)).scaleX(0.86f).scaleY(1.12f);
                break;
            case "drop":
                animator.alpha(0f).translationY(dp(view, 78)).scaleX(0.94f).scaleY(0.94f);
                break;
            case "swing":
                animator.alpha(0f).translationX(dp(view, 38)).rotation(-14f).scaleX(0.9f).scaleY(0.9f);
                break;
            case "fold":
                animator.alpha(0f).scaleY(0.14f).rotationX(24f);
                break;
            case "collapse":
                animator.alpha(0f).scaleX(0.20f).scaleY(0.20f);
                break;
            case "cube":
                animator.alpha(0f).translationX(-dp(view, 28)).rotationY(-72f).scaleX(0.84f);
                break;
            case "shrink":''',
    "exit styles",
)

engine = replace_once(
    engine,
    '''            } else if ("spring".equals(style)) {
                outgoing.animate().alpha(0f).scaleX(0.86f).scaleY(1.08f).setDuration(duration / 2).start();
            } else {
                outgoing.animate().alpha(0f).setDuration(duration / 2).start();
            }''',
    '''            } else if ("spring".equals(style)) {
                outgoing.animate().alpha(0f).scaleX(0.86f).scaleY(1.08f).setDuration(duration / 2).start();
            } else if ("parallax".equals(style)) {
                outgoing.animate().alpha(0.25f).translationX(-dp(outgoing, 58)).scaleX(0.96f).scaleY(0.96f)
                        .setDuration(duration / 2).start();
            } else if ("swing".equals(style)) {
                outgoing.animate().alpha(0f).translationX(-dp(outgoing, 24)).rotation(-10f)
                        .setDuration(duration / 2).start();
            } else if ("fold".equals(style)) {
                outgoing.animate().alpha(0f).scaleY(0.18f).rotationX(18f).setDuration(duration / 2).start();
            } else if ("cube".equals(style)) {
                outgoing.animate().alpha(0f).rotationY(-68f).translationX(-dp(outgoing, 24))
                        .setDuration(duration / 2).start();
            } else if ("reveal".equals(style)) {
                outgoing.animate().alpha(0f).scaleX(1.05f).scaleY(1.05f).setDuration(duration / 2).start();
            } else {
                outgoing.animate().alpha(0f).setDuration(duration / 2).start();
            }''',
    "switch outgoing styles",
)

engine = replace_once(
    engine,
    '''        } else if ("spring".equals(style)) {
            incoming.setScaleX(0.76f);
            incoming.setScaleY(1.08f);
        }
        incoming.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
                .rotation(0f).rotationY(0f).setDuration(duration)''',
    '''        } else if ("spring".equals(style)) {
            incoming.setScaleX(0.76f);
            incoming.setScaleY(1.08f);
        } else if ("parallax".equals(style)) {
            incoming.setTranslationX(dp(incoming, 64));
            incoming.setScaleX(1.03f);
            incoming.setScaleY(1.03f);
        } else if ("swing".equals(style)) {
            incoming.setTranslationX(dp(incoming, 32));
            incoming.setRotation(12f);
            incoming.setScaleX(0.92f);
            incoming.setScaleY(0.92f);
        } else if ("fold".equals(style)) {
            incoming.setScaleY(0.18f);
            incoming.setRotationX(-22f);
        } else if ("cube".equals(style)) {
            incoming.setTranslationX(dp(incoming, 28));
            incoming.setRotationY(72f);
            incoming.setScaleX(0.86f);
        } else if ("reveal".equals(style)) {
            incoming.setScaleX(0.72f);
            incoming.setScaleY(0.72f);
        }
        incoming.animate().alpha(1f).translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                .rotation(0f).rotationX(0f).rotationY(0f).setDuration(duration)''',
    "switch incoming styles",
)

engine = replace_once(
    engine,
    '''                case "spring":
                    child.setScaleX(0.74f);
                    child.setScaleY(1.1f);
                    break;
                case "crossfade":''',
    '''                case "spring":
                    child.setScaleX(0.74f);
                    child.setScaleY(1.1f);
                    break;
                case "parallax":
                    child.setTranslationX(dp(child, delta >= 0 ? 54 : -54));
                    child.setScaleX(0.96f);
                    child.setScaleY(0.96f);
                    break;
                case "swing":
                    child.setTranslationX(dp(child, delta >= 0 ? 30 : -30));
                    child.setRotation(delta >= 0 ? 10f : -10f);
                    break;
                case "fold":
                    child.setScaleY(0.22f);
                    child.setRotationX(delta >= 0 ? -18f : 18f);
                    break;
                case "cube":
                    child.setRotationY(delta >= 0 ? 64f : -64f);
                    child.setScaleX(0.86f);
                    break;
                case "reveal":
                    child.setScaleX(0.68f);
                    child.setScaleY(0.68f);
                    break;
                case "crossfade":''',
    "list move styles",
)
engine = replace_once(
    engine,
    '''                    .rotation(0f)
                    .rotationY(0f)
                    .setDuration(duration)''',
    '''                    .rotation(0f)
                    .rotationX(0f)
                    .rotationY(0f)
                    .setDuration(duration)''',
    "list move rotation reset",
)

engine = replace_once(
    engine,
    '''            case "bounce":
                child.setTranslationY(dp(child, 46));
                child.setScaleX(1.12f);
                child.setScaleY(0.72f);
                break;
            case "classic":''',
    '''            case "bounce":
                child.setTranslationY(dp(child, 46));
                child.setScaleX(1.12f);
                child.setScaleY(0.72f);
                break;
            case "parallax":
                child.setTranslationX(dp(child, (index & 1) == 0 ? 68 : -68));
                child.setTranslationY(dp(child, 18));
                child.setScaleX(0.96f);
                child.setScaleY(0.96f);
                break;
            case "swing":
                child.setTranslationX(dp(child, (index & 1) == 0 ? 34 : -34));
                child.setRotation((index & 1) == 0 ? 13f : -13f);
                child.setScaleX(0.92f);
                child.setScaleY(0.92f);
                break;
            case "accordion":
                child.setScaleY(0.16f);
                child.setTranslationY(dp(child, 28));
                child.setRotationX((index & 1) == 0 ? -16f : 16f);
                break;
            case "spiral":
                child.setTranslationX(dp(child, (index & 1) == 0 ? 54 : -54));
                child.setTranslationY(dp(child, 34));
                child.setRotation((index & 1) == 0 ? 22f : -22f);
                child.setScaleX(0.72f);
                child.setScaleY(0.72f);
                break;
            case "cube":
                child.setTranslationX(dp(child, (index & 1) == 0 ? 40 : -40));
                child.setRotationY((index & 1) == 0 ? 68f : -68f);
                child.setScaleX(0.84f);
                child.setScaleY(0.94f);
                break;
            case "classic":''',
    "scroll styles",
)
engine = replace_once(
    engine,
    '''                .setInterpolator(("depth".equals(style) || "stack".equals(style)
                        || "bounce".equals(style))''',
    '''                .setInterpolator(("depth".equals(style) || "stack".equals(style)
                        || "bounce".equals(style) || "swing".equals(style)
                        || "accordion".equals(style))''',
    "scroll interpolator",
)
write(engine_path, engine)

# Wire the existing Window enter/exit preferences into Home lifecycle. -----------
main_path = "app/src/main/java/fr/neamar/kiss/MainActivity.java"
main = read(main_path)
main = replace_once(
    main,
    'import fr.neamar.kiss.ui.SearchEditText;\n',
    'import fr.neamar.kiss.ui.SearchEditText;\nimport fr.neamar.kiss.ui.SmartAnimationEngine;\n',
    "MainActivity animation import",
)
main = replace_once(
    main,
    '''        super.onResume();
        homeLifecycleState.onResumeCompleted();
    }


    @Override
    protected void onPause() {
        launcherUiResumed = false;
        forwarderManager.onPause();
        super.onPause();
    }''',
    '''        super.onResume();
        homeLifecycleState.onResumeCompleted();
        // Window animation is launcher-owned and starts only after Home has resumed.
        SmartAnimationEngine.animateWindowEnter(findViewById(android.R.id.content));
    }


    @Override
    protected void onPause() {
        // Do not delay external launches: animate our own decor while Android transfers focus.
        SmartAnimationEngine.animateWindowExit(findViewById(android.R.id.content));
        launcherUiResumed = false;
        forwarderManager.onPause();
        super.onPause();
    }''',
    "MainActivity window lifecycle wiring",
)
write(main_path, main)

# Keep the live animation preview truthful for every new scroll style. -----------
preview_path = "app/src/main/java/fr/neamar/kiss/preference/UiLivePreviewPreference.java"
preview = read(preview_path)
preview = replace_once(
    preview,
    '''                    case "cascade": y += (1f - phase) * dp(32); alpha = .55f + phase * .45f; break;
                    case "focus":
                    case "depth": scale = .88f + phase * .14f; alpha = .72f + phase * .28f; break;
                    default: y += (1f - phase) * dp(14); break;''',
    '''                    case "cascade": y += (1f - phase) * dp(32); alpha = .55f + phase * .45f; break;
                    case "parallax": x += (phase - .5f) * dp(110); y += (1f - phase) * dp(10); break;
                    case "swing": x += (phase - .5f) * dp(48); rot = -14f + phase * 28f; break;
                    case "accordion": scale = .72f + phase * .28f; y += (1f - phase) * dp(24); break;
                    case "spiral": x += (phase - .5f) * dp(64); y += (1f - phase) * dp(30); rot = -24f + phase * 48f; scale = .72f + phase * .28f; break;
                    case "cube": x += (phase - .5f) * dp(46); scale = .78f + phase * .22f; alpha = .62f + phase * .38f; break;
                    case "focus":
                    case "depth": scale = .88f + phase * .14f; alpha = .72f + phase * .28f; break;
                    default: y += (1f - phase) * dp(14); break;''',
    "live preview scroll styles",
)
write(preview_path, preview)

print("Smart S Launcher 3.30.85 animation patch applied successfully")
