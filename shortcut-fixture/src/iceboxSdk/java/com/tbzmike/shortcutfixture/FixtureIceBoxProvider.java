package com.tbzmike.shortcutfixture;

import android.app.PendingIntent;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** Test-only SDK boundary whose enable operation completes after the provider returns. */
public final class FixtureIceBoxProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        PendingIntent auth = extras == null ? null : extras.getParcelable("authorize");
        String[] names = extras == null ? null : extras.getStringArray("package_names");
        if (!"set_enable".equals(method) || auth == null || names == null || names.length != 1
                || !extras.getBoolean("enable")) throw new IllegalArgumentException("Invalid SDK call");
        Intent request = new Intent("com.tbzmike.shortcutfixture.ICEBOX_SDK")
                .setPackage(auth.getCreatorPackage()).putExtra("target", names[0]);
        getContext().sendOrderedBroadcast(request, null);
        return new Bundle();
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
