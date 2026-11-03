package com.tbzmike.shortcutfixture;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Test-only stand-in for Ice Box's documented synchronous SDK call. */
public final class FixtureIceBoxProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        PendingIntent auth = extras == null ? null : extras.getParcelable("authorize");
        String[] names = extras == null ? null : extras.getStringArray("package_names");
        if (!"set_enable".equals(method) || auth == null || names == null || names.length != 1
                || !extras.getBoolean("enable")) throw new IllegalArgumentException("Invalid SDK call");
        CountDownLatch completed = new CountDownLatch(1);
        Intent request = new Intent("com.tbzmike.shortcutfixture.ICEBOX_SDK")
                .setPackage(auth.getCreatorPackage()).putExtra("target", names[0]);
        getContext().sendOrderedBroadcast(request, null, new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { completed.countDown(); }
        }, null, 0, null, null);
        try { completed.await(3, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return new Bundle();
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
