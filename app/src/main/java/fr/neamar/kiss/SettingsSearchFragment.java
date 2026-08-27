package fr.neamar.kiss;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import java.util.List;

import fr.neamar.kiss.ui.SettingsSearchIndex;

/** Live results surface used by the Settings toolbar search field. */
public class SettingsSearchFragment extends PreferenceFragmentCompat {
    private static final String ARG_QUERY = "settings_search_query";
    private String query = "";

    public static SettingsSearchFragment newInstance(@NonNull String query) {
        SettingsSearchFragment fragment = new SettingsSearchFragment();
        Bundle args = new Bundle();
        args.putString(ARG_QUERY, query);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        Bundle args = getArguments();
        query = args == null ? "" : args.getString(ARG_QUERY, "");
        rebuildResults();
    }

    public void setQuery(@NonNull String newQuery) {
        query = newQuery;
        if (isAdded()) rebuildResults();
    }

    private void rebuildResults() {
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
        screen.setTitle("Search settings");
        setPreferenceScreen(screen);

        String clean = query.trim();
        if (clean.isEmpty()) return;

        List<SettingsSearchIndex.Entry> results = SettingsSearchIndex.search(requireContext(), clean);
        if (results.isEmpty()) {
            Preference empty = new Preference(requireContext());
            empty.setTitle("No settings found");
            empty.setSummary("Try another word, setting name or feature.");
            empty.setSelectable(false);
            screen.addPreference(empty);
            return;
        }

        for (SettingsSearchIndex.Entry entry : results) {
            Preference result = new Preference(requireContext());
            result.setTitle(entry.title);
            String location = locationFor(entry);
            if (entry.summary == null || entry.summary.isEmpty()) {
                result.setSummary(location);
            } else if (location.isEmpty()) {
                result.setSummary(entry.summary);
            } else {
                result.setSummary(entry.summary + "  •  " + location);
            }
            result.setOnPreferenceClickListener(preference -> {
                if (getActivity() instanceof SettingsActivity) {
                    ((SettingsActivity) getActivity()).openSettingsSearchResult(entry);
                }
                return true;
            });
            screen.addPreference(result);
        }
    }

    private String locationFor(SettingsSearchIndex.Entry entry) {
        switch (entry.destination) {
            case SMART_FEATURES:
                return "Smart features";
            case SMART_SECTION:
                return "Smart features › " + (entry.section == null ? "section" : entry.section);
            case BATTERY_MONITOR:
                return "Battery monitor";
            case INDEXING_SETTINGS:
                return "Indexing";
            case APP_USAGE:
                return "App usage";
            case STANDARD:
            default:
                return entry.rootKey == null ? "Settings" : "Settings › " + entry.rootKey;
        }
    }
}
