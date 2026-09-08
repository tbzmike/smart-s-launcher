from pathlib import Path


def patch(path, old, new, count=1):
    p = Path(path)
    text = p.read_text()
    found = text.count(old)
    if found != count:
        raise SystemExit(f"{path}: expected {count}, found {found}: {old[:140]!r}")
    p.write_text(text.replace(old, new, count))


# This correction runs after the original guarded 3.30.66 transform. It fixes the exact lint
# failure, identifies notifications from what is actually rendered/persisted, and makes HOME
# publish a real HISTORY result set instead of only blanking the query text.

# SmartCardListForwarder: remove API-29-only suppressLayout(), fix stale changed-view removal,
# and identify persisted notification-enriched AppPojo cards by actual rendered content.
s = 'app/src/main/java/fr/neamar/kiss/forwarder/SmartCardListForwarder.java'
patch(s,
      '''import fr.neamar.kiss.pojo.CommunicationPojo;\nimport fr.neamar.kiss.pojo.NotificationHistorySearchPojo;\nimport fr.neamar.kiss.pojo.NotificationPojo;\nimport fr.neamar.kiss.result.AppResult;''',
      '''import fr.neamar.kiss.pojo.CommunicationPojo;\nimport fr.neamar.kiss.result.AppResult;''')
patch(s,
      '''        Map<String, String> nextSignatures = new HashMap<>();\n        int targetCount = mainActivity.adapter.getCount();\n        column.suppressLayout(true);\n        try {\n            for (int position = 0; position < targetCount; position++) {\n                Result<?> result = mainActivity.adapter.getItem(position);\n                String id = result.getPojoId();\n                String signature = activeQueryCardSignature(result);\n                nextSignatures.put(id, signature);\n\n                View desired = existingById.remove(id);\n                if (desired == null\n                        || !TextUtils.equals(activeQueryCardSignatures.get(id), signature)) {\n                    View source = mainActivity.adapter.getView(position, null, column);\n                    desired = createCardItem(\n                            source, result, position, Collections.emptyMap());\n                }\n                placeActiveQueryChild(desired, position);\n            }\n\n            while (column.getChildCount() > targetCount) {\n                column.removeViewAt(column.getChildCount() - 1);\n            }\n        } finally {\n            column.suppressLayout(false);\n        }\n        activeQueryCardSignatures.clear();''',
      '''        Map<String, String> nextSignatures = new HashMap<>();\n        int targetCount = mainActivity.adapter.getCount();\n        for (int position = 0; position < targetCount; position++) {\n            Result<?> result = mainActivity.adapter.getItem(position);\n            String id = result.getPojoId();\n            String signature = activeQueryCardSignature(result);\n            nextSignatures.put(id, signature);\n\n            View desired = existingById.remove(id);\n            if (desired != null\n                    && !TextUtils.equals(activeQueryCardSignatures.get(id), signature)) {\n                if (desired.getParent() == column) column.removeView(desired);\n                desired = null;\n            }\n            if (desired == null) {\n                View source = mainActivity.adapter.getView(position, null, column);\n                desired = createCardItem(\n                        source, result, position, Collections.emptyMap());\n            }\n            placeActiveQueryChild(desired, position);\n        }\n\n        // Entries left in the map disappeared from the new query result set. Remove those exact\n        // stale Views rather than trimming arbitrary children from the end after reordering.\n        for (View stale : existingById.values()) {\n            if (stale.getParent() == column) column.removeView(stale);\n        }\n        while (column.getChildCount() > targetCount) {\n            column.removeViewAt(column.getChildCount() - 1);\n        }\n        activeQueryCardSignatures.clear();''')
patch(s,
      '''        NotificationBellStyle.apply(cardTitle, isNotificationResult(result));\n        center.addView(cardTitle,''',
      '''        NotificationBellStyle.apply(cardTitle,\n                NotificationBellStyle.isNotificationItem(mainActivity, result, source)\n                        || hasActiveNotification || hasMessage);\n        center.addView(cardTitle,''')
patch(s,
      '''    private boolean isNotificationResult(Result<?> result) {\n        Object pojo = result == null ? null : result.getPojo();\n        return pojo instanceof NotificationPojo || pojo instanceof NotificationHistorySearchPojo;\n    }\n\n''',
      '')

# SettingsResult no longer owns bell policy. RecordAdapter applies one central identity rule after
# every result renderer has produced its actual row, which also guarantees recycled rows are reset.
s = 'app/src/main/java/fr/neamar/kiss/result/SettingsResult.java'
patch(s,
      '''import fr.neamar.kiss.ui.CompactNotificationFrame;\nimport fr.neamar.kiss.ui.NotificationBellStyle;\nimport fr.neamar.kiss.ui.SmartAnimationEngine;''',
      '''import fr.neamar.kiss.ui.CompactNotificationFrame;\nimport fr.neamar.kiss.ui.SmartAnimationEngine;''')
patch(s,
      '''        TextView settingName = view.findViewById(R.id.item_setting_name);\n        displayHighlighted(pojo.normalizedName, pojo.getName(), fuzzyScore, settingName, context);\n        // Explicitly clear on ordinary recycled setting rows so a previous notification marker\n        // can never leak to another result. Persisted notification-search rows keep the bell.\n        NotificationBellStyle.apply(settingName, pojo instanceof NotificationHistorySearchPojo);''',
      '''        TextView settingName = view.findViewById(R.id.item_setting_name);\n        displayHighlighted(pojo.normalizedName, pojo.getName(), fuzzyScore, settingName, context);''')
patch(s,
      '''        appName.setText(notification.appName);\n        NotificationBellStyle.apply(appName, true);\n        title.setText(notification.getSummary());''',
      '''        appName.setText(notification.appName);\n        title.setText(notification.getSummary());''')

# Shared notification identity: direct notification POJOs, Truecaller notification records, live
# rendered notification rows, and persisted app-history notification content are all notifications.
Path('app/src/main/java/fr/neamar/kiss/ui/NotificationBellStyle.java').write_text('''package fr.neamar.kiss.ui;\n\nimport android.content.Context;\nimport android.graphics.drawable.Drawable;\nimport android.text.TextUtils;\nimport android.view.View;\nimport android.widget.TextView;\n\nimport androidx.annotation.NonNull;\nimport androidx.annotation.Nullable;\nimport androidx.core.content.ContextCompat;\nimport androidx.core.graphics.drawable.DrawableCompat;\nimport androidx.preference.PreferenceManager;\n\nimport fr.neamar.kiss.R;\nimport fr.neamar.kiss.db.NotificationHistoryRecord;\nimport fr.neamar.kiss.db.SmartStateStore;\nimport fr.neamar.kiss.pojo.AppPojo;\nimport fr.neamar.kiss.pojo.CommunicationPojo;\nimport fr.neamar.kiss.pojo.NotificationHistorySearchPojo;\nimport fr.neamar.kiss.pojo.NotificationPojo;\nimport fr.neamar.kiss.pojo.Pojo;\nimport fr.neamar.kiss.result.Result;\nimport fr.neamar.kiss.searcher.SearchHandler;\nimport fr.neamar.kiss.searcher.Searcher;\n\n/** One notification identity rule shared by native and custom launcher result renderers. */\npublic final class NotificationBellStyle {\n    private NotificationBellStyle() {}\n\n    public static boolean isNotificationItem(@NonNull Context context,\n                                             @Nullable Result<?> result,\n                                             @Nullable View renderedSource) {\n        if (result == null || result.getPojo() == null) return false;\n        Pojo pojo = result.getPojo();\n\n        if (pojo instanceof NotificationPojo || pojo instanceof NotificationHistorySearchPojo) {\n            return true;\n        }\n        if (pojo instanceof CommunicationPojo\n                && ((CommunicationPojo) pojo).kind == CommunicationPojo.Kind.TRUECALLER_NOTIFICATION) {\n            return true;\n        }\n        if (hasRenderedNotification(renderedSource)) return true;\n\n        // Vertical Cards already present the latest persisted notification on ordinary app history\n        // rows. Apply the same identity in every HISTORY renderer so those old notification items\n        // do not lose their bell merely because the underlying launcher POJO is still AppPojo.\n        if (pojo instanceof AppPojo\n                && SearchHandler.getInstance().getLastSearchType() == Searcher.Type.HISTORY\n                && PreferenceManager.getDefaultSharedPreferences(context)\n                        .getBoolean("enable-notification-history", false)) {\n            NotificationHistoryRecord latest = SmartStateStore.latestNotificationForPackage(\n                    context, ((AppPojo) pojo).packageName);\n            return latest != null\n                    && (!TextUtils.isEmpty(latest.title) || !TextUtils.isEmpty(latest.text));\n        }\n        return false;\n    }\n\n    public static boolean hasRenderedNotification(@Nullable View root) {\n        if (root == null) return false;\n        View row = root.findViewById(R.id.item_notification_row);\n        return row != null && row.getVisibility() == View.VISIBLE;\n    }\n\n    /** Apply to the primary name in a normal adapter row; false explicitly clears recycled rows. */\n    public static void applyToResult(@NonNull View root, @Nullable Result<?> result) {\n        TextView primary = primaryName(root);\n        if (primary != null) apply(primary, isNotificationItem(root.getContext(), result, root));\n    }\n\n    @Nullable\n    private static TextView primaryName(@NonNull View root) {\n        int[] ids = new int[]{R.id.item_notification_app, R.id.item_app_name,\n                R.id.item_setting_name, R.id.item_communication_title};\n        for (int id : ids) {\n            View candidate = root.findViewById(id);\n            if (candidate instanceof TextView && candidate.getVisibility() != View.GONE) {\n                return (TextView) candidate;\n            }\n        }\n        return null;\n    }\n\n    public static void apply(@NonNull TextView textView, boolean visible) {\n        if (!visible) {\n            textView.setCompoundDrawablesRelative(null, null, null, null);\n            textView.setCompoundDrawablePadding(0);\n            return;\n        }\n        Drawable source = ContextCompat.getDrawable(\n                textView.getContext(), R.drawable.ic_notification_bell);\n        if (source == null) return;\n        Drawable bell = DrawableCompat.wrap(source.mutate());\n        DrawableCompat.setTint(bell, textView.getCurrentTextColor());\n        float density = textView.getResources().getDisplayMetrics().density;\n        int size = Math.max(1, Math.round(17f * density));\n        bell.setBounds(0, 0, size, size);\n        textView.setCompoundDrawablePadding(Math.round(5f * density));\n        textView.setCompoundDrawablesRelative(null, null, bell, null);\n    }\n}\n''')

# Native Vertical List and 3D Wheel share RecordAdapter rows, so apply the central bell policy once
# after the real row has been rendered.
s = 'app/src/main/java/fr/neamar/kiss/adapter/RecordAdapter.java'
patch(s,
      '''import fr.neamar.kiss.ui.LaunchMorphTransition;\nimport fr.neamar.kiss.ui.ListPopup;''',
      '''import fr.neamar.kiss.ui.LaunchMorphTransition;\nimport fr.neamar.kiss.ui.ListPopup;\nimport fr.neamar.kiss.ui.NotificationBellStyle;''')
patch(s,
      '''        Result<?> result = getItem(position);\n        View view = result.display(parent.getContext(), convertView, parent, fuzzyScore);\n        if (result.getPojo() instanceof NotificationPojo) {''',
      '''        Result<?> result = getItem(position);\n        View view = result.display(parent.getContext(), convertView, parent, fuzzyScore);\n        NotificationBellStyle.applyToResult(view, result);\n        if (result.getPojo() instanceof NotificationPojo) {''')

# Horizontal Icons/Names/Cards and Square U create their own TextViews, so explicitly transfer the
# central notification identity onto those generated labels. Wheel uses the adapter row above.
s = 'app/src/main/java/fr/neamar/kiss/forwarder/HistoryDisplayForwarder.java'
patch(s,
      '''import fr.neamar.kiss.ui.AutoMarqueeTextView;\nimport fr.neamar.kiss.ui.SmartAnimationEngine;''',
      '''import fr.neamar.kiss.ui.AutoMarqueeTextView;\nimport fr.neamar.kiss.ui.NotificationBellStyle;\nimport fr.neamar.kiss.ui.SmartAnimationEngine;''')
patch(s,
      '''        addFullLabel(tile, label, 12f, dp(44) * tilePercent / 100, 1);\n        return tile;''',
      '''        TextView labelView = addFullLabel(tile, label, 12f, dp(44) * tilePercent / 100, 1);\n        NotificationBellStyle.apply(labelView,\n                NotificationBellStyle.isNotificationItem(mainActivity, result, source));\n        return tile;''')
patch(s,
      '''        TextView name = buildMarqueeLabel(extractLabel(source), 16f);\n        name.setPadding(dp(8), dp(7), dp(8), dp(7));''',
      '''        TextView name = buildMarqueeLabel(extractLabel(source), 16f);\n        NotificationBellStyle.apply(name,\n                NotificationBellStyle.isNotificationItem(mainActivity, result, source));\n        name.setPadding(dp(8), dp(7), dp(8), dp(7));''')
patch(s,
      '''        ImageView icon = addForegroundIconAndLabel(card, iconDrawable, label,\n                dp(58) * iconPercent / 100, 14f, dp(14));''',
      '''        boolean notificationItem = NotificationBellStyle.isNotificationItem(\n                mainActivity, result, source);\n        ImageView icon = addForegroundIconAndLabel(card, iconDrawable, label,\n                dp(58) * iconPercent / 100, 14f, dp(14), notificationItem);''')
patch(s,
      '''        addFullLabel(card, label, 14f, dp(42), 1);\n        return card;''',
      '''        TextView labelView = addFullLabel(card, label, 14f, dp(42), 1);\n        NotificationBellStyle.apply(labelView,\n                NotificationBellStyle.isNotificationItem(mainActivity, result, source));\n        return card;''')
patch(s,
      '''    private ImageView addForegroundIconAndLabel(FrameLayout card, Drawable iconDrawable,\n                                                CharSequence label, int iconSize,\n                                                float textSize, int topMargin) {''',
      '''    private ImageView addForegroundIconAndLabel(FrameLayout card, Drawable iconDrawable,\n                                                CharSequence label, int iconSize,\n                                                float textSize, int topMargin,\n                                                boolean notificationItem) {''')
patch(s,
      '''        card.addView(icon, iconParams);\n        addFullLabel(card, label, textSize, dp(42), 1);\n        return icon;''',
      '''        card.addView(icon, iconParams);\n        TextView labelView = addFullLabel(card, label, textSize, dp(42), 1);\n        NotificationBellStyle.apply(labelView, notificationItem);\n        return icon;''')
patch(s,
      '''    private void addFullLabel(FrameLayout card, CharSequence label, float textSize,\n                              int labelHeight, int maxLines) {''',
      '''    private TextView addFullLabel(FrameLayout card, CharSequence label, float textSize,\n                              int labelHeight, int maxLines) {''')
patch(s,
      '''        nameParams.bottomMargin = dp(5);\n        card.addView(name, nameParams);\n    }''',
      '''        nameParams.bottomMargin = dp(5);\n        card.addView(name, nameParams);\n        return name;\n    }''')

# Search/Home: HOME is allowed to decide from either still-visible query text or the last published
# QUERY type before onNewIntent clears text. onResume then publishes a real HISTORY search.
s = 'app/src/main/java/fr/neamar/kiss/SearchLaunchReturnState.java'
patch(s,
      '''    void onExternalLaunchCancelled() {\n        startedFromSearch = false;\n    }\n\n    boolean consumeDefaultHistoryReset() {''',
      '''    void onExternalLaunchCancelled() {\n        startedFromSearch = false;\n    }\n\n    void onHomeIntent(boolean searchResultsActive) {\n        if (searchResultsActive) defaultHistoryResetPending = true;\n    }\n\n    boolean consumeDefaultHistoryReset() {''')

s = 'app/src/main/java/fr/neamar/kiss/MainActivity.java'
patch(s,
      '''        if (resetDefaultHistoryAfterSearchLaunch) {\n            forwarderManager.prepareDefaultHistoryAfterSearchLaunch();\n            cancelSearch();\n            if (!TextUtils.isEmpty(searchEditText.getText())) {\n                clearSearchText();\n            } else {\n                updateSearchRecords(false, "");\n            }\n            displayClearOnInput();\n            hideKeyboard();''',
      '''        if (resetDefaultHistoryAfterSearchLaunch) {\n            forwarderManager.prepareDefaultHistoryAfterSearchLaunch();\n            cancelSearch();\n            if (!TextUtils.isEmpty(searchEditText.getText())) {\n                clearSearchText();\n            }\n            // Emptying the EditText only changes UI chrome; it does not publish HISTORY. Always\n            // replace the old QUERY adapter with the actual default history result set.\n            showHistory();\n            displayClearOnInput();\n            hideKeyboard();''')
patch(s,
      '''        //Set the intent so KISS can tell when it was launched as an assistant\n        setIntent(intent);\n\n        // singleTask routes both a Home return from another app and a second Home press here.''',
      '''        // Capture search-result state before HOME clears the EditText. A completed external\n        // launch may already have blanked the field while SearchHandler still owns QUERY results.\n        boolean homeIntent = Intent.ACTION_MAIN.equals(intent.getAction())\n                && intent.hasCategory(Intent.CATEGORY_HOME);\n        if (homeIntent) {\n            Searcher.Type lastSearchType = SearchHandler.getInstance().getLastSearchType();\n            boolean searchResultsActive = !TextUtils.isEmpty(searchEditText.getText())\n                    || lastSearchType == Searcher.Type.QUERY;\n            searchLaunchReturnState.onHomeIntent(searchResultsActive);\n        }\n\n        //Set the intent so KISS can tell when it was launched as an assistant\n        setIntent(intent);\n\n        // singleTask routes both a Home return from another app and a second Home press here.''')

s = 'app/src/test/java/fr/neamar/kiss/SearchLaunchReturnStateTest.java'
patch(s,
      '''    @Test void cancelledSearchLaunchDoesNotResetHistory() {\n        SearchLaunchReturnState state = new SearchLaunchReturnState();\n        state.onExternalLaunchStarting(true);\n        state.onExternalLaunchCancelled();\n        assertThat(state.consumeDefaultHistoryReset(), is(false));\n    }\n}''',
      '''    @Test void cancelledSearchLaunchDoesNotResetHistory() {\n        SearchLaunchReturnState state = new SearchLaunchReturnState();\n        state.onExternalLaunchStarting(true);\n        state.onExternalLaunchCancelled();\n        assertThat(state.consumeDefaultHistoryReset(), is(false));\n    }\n\n    @Test void homeFromQueryRequestsExactlyOneDefaultHistoryReset() {\n        SearchLaunchReturnState state = new SearchLaunchReturnState();\n        state.onHomeIntent(true);\n        assertThat(state.consumeDefaultHistoryReset(), is(true));\n        assertThat(state.consumeDefaultHistoryReset(), is(false));\n    }\n\n    @Test void homeFromRealHistoryDoesNotRequestSearchReset() {\n        SearchLaunchReturnState state = new SearchLaunchReturnState();\n        state.onHomeIntent(false);\n        assertThat(state.consumeDefaultHistoryReset(), is(false));\n    }\n}''')
