package fr.neamar.kiss.result;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import java.io.IOException;
import java.io.InputStream;

import fr.neamar.kiss.IconsHandler;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.R;
import fr.neamar.kiss.adapter.RecordAdapter;
import fr.neamar.kiss.icons.IconPack;
import fr.neamar.kiss.pojo.ContactsPojo;
import fr.neamar.kiss.searcher.QueryInterface;
import fr.neamar.kiss.ui.ImprovedQuickContactBadge;
import fr.neamar.kiss.ui.ListPopup;
import fr.neamar.kiss.ui.ShapedContactBadge;
import fr.neamar.kiss.utils.ClipboardUtils;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.MimeTypeUtils;
import fr.neamar.kiss.utils.PackageManagerUtils;
import fr.neamar.kiss.utils.fuzzy.FuzzyScore;

public class ContactsResult extends CallResult<ContactsPojo> {

    private static final int MAX_CACHED_CONTACT_PHOTO_EDGE = 256;
    private final QueryInterface queryInterface;
    private volatile Drawable icon = null;
    private volatile Drawable appDrawable = null;
    private static final String TAG = ContactsResult.class.getSimpleName();

    ContactsResult(QueryInterface queryInterface, @NonNull ContactsPojo pojo) {
        super(pojo);
        this.queryInterface = queryInterface;
    }

    @NonNull
    @Override
    public View display(Context context, View view, @NonNull ViewGroup parent, FuzzyScore fuzzyScore) {
        if (view == null)
            view = inflateFromId(context, R.layout.item_contact, parent);

        TextView contactName = view.findViewById(R.id.item_contact_name);
        if (!TextUtils.isEmpty(pojo.getName())) {
            displayHighlighted(pojo.normalizedName, pojo.getName(), fuzzyScore, contactName, context);
        }

        TextView contactPhone = view.findViewById(R.id.item_contact_phone);
        if (pojo.getContactData() != null && !TextUtils.isEmpty(pojo.getContactData().getIdentifier())) {
            contactPhone.setVisibility(View.VISIBLE);
            displayHighlighted(pojo.getContactData().getNormalizedIdentifier(), pojo.getContactData().getIdentifier(), fuzzyScore, contactPhone, context);
        } else if (!TextUtils.isEmpty(pojo.phone)) {
            contactPhone.setVisibility(View.VISIBLE);
            displayHighlighted(pojo.normalizedPhone, pojo.phone, fuzzyScore, contactPhone, context);
        } else {
            contactPhone.setVisibility(View.GONE);
        }

        TextView contactNickname = view.findViewById(R.id.item_contact_nickname);
        if (TextUtils.isEmpty(pojo.getNickname())) {
            contactNickname.setVisibility(View.GONE);
        } else {
            contactNickname.setVisibility(View.VISIBLE);
            displayHighlighted(pojo.normalizedNickname, pojo.getNickname(), fuzzyScore, contactNickname, context);
        }

        ImprovedQuickContactBadge contactIcon = view.findViewById(R.id.item_contact_icon);
        boolean hideIcons = isHideIcons(context);
        if (!hideIcons) this.setAsyncDrawable(contactIcon);
        else contactIcon.setImageDrawable(null);

        Uri contactUri = ContactsContract.Contacts.CONTENT_LOOKUP_URI;
        contactUri = Uri.withAppendedPath(contactUri, String.valueOf(pojo.lookupKey));
        contactUri = Uri.withAppendedPath(contactUri, String.valueOf(pojo.getContactId()));
        contactIcon.assignContactUri(contactUri);
        contactIcon.setExtraOnClickListener(v -> recordLaunch(v.getContext(), queryInterface));

        PackageManager pm = context.getPackageManager();
        boolean hasPhone = pojo.phone != null && pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY);

        ImageButton phoneButton = view.findViewById(R.id.item_contact_action_phone);
        if (hasPhone) {
            phoneButton.setVisibility(View.VISIBLE);
            phoneButton.setOnClickListener(v -> {
                launchCall(v.getContext(), v, pojo.phone);
                recordLaunch(context, queryInterface);
            });
        } else {
            phoneButton.setVisibility(View.GONE);
        }

        ImageButton messageButton = view.findViewById(R.id.item_contact_action_message);
        if (pojo.getContactData() != null) {
            messageButton.setVisibility(View.VISIBLE);
            messageButton.setOnClickListener(v -> {
                launchIm(v.getContext(), v);
                recordLaunch(context, queryInterface);
            });
        } else if (hasPhone) {
            messageButton.setVisibility(View.VISIBLE);
            messageButton.setOnClickListener(v -> {
                launchMessaging(v.getContext(), v);
                recordLaunch(context, queryInterface);
            });
            messageButton.setVisibility(pojo.isHomeNumber() ? View.INVISIBLE : View.VISIBLE);
        } else {
            messageButton.setVisibility(View.INVISIBLE);
        }

        final ImageView appIcon = view.findViewById(R.id.item_app_icon);
        if (pojo.getContactData() != null && !hideIcons && isSubIconVisible(context)) {
            appIcon.setVisibility(View.VISIBLE);
            setAsyncDrawable(appIcon, R.drawable.ic_launcher_white, false, () -> appDrawable != null,
                    this::getAppDrawable, (drawable) -> appDrawable = drawable);
        } else {
            appIcon.setVisibility(View.GONE);
        }
        return view;
    }

    private Drawable getAppDrawable(Context context) {
        if (appDrawable == null) {
            synchronized (this) {
                if (appDrawable == null) {
                    ComponentName componentName = KissApplication.getMimeTypeCache(context)
                            .getComponentName(context, pojo.getContactData().getMimeType());
                    if (componentName != null) {
                        IconsHandler iconsHandler = KissApplication.getApplication(context).getIconsHandler();
                        appDrawable = iconsHandler.getDrawableIconForPackage(
                                PackageManagerUtils.getLaunchingComponent(context, componentName, pojo.getUserHandle()),
                                pojo.getUserHandle());
                    }
                    if (appDrawable == null) appDrawable = context.getPackageManager().getDefaultActivityIcon();
                }
            }
        }
        return appDrawable;
    }

    @Override
    protected void buildPopupMenu(Context context, ArrayAdapter<ListPopup.Item> adapter) {
        super.buildPopupMenu(context, adapter);
        adapter.add(new ListPopup.Item(context, R.string.menu_contact_copy_phone));
    }

    @Override
    protected boolean popupMenuClickHandler(Context context, RecordAdapter parent, int stringId, View parentView) {
        if (stringId == R.string.menu_contact_copy_phone) {
            copyPhone(context, pojo);
            return true;
        }
        return super.popupMenuClickHandler(context, parent, stringId, parentView);
    }

    private void copyPhone(Context context, ContactsPojo pojo) {
        ClipboardUtils.setClipboard(context, "Phone number for " + pojo.getName(), pojo.phone);
    }

    @Override boolean isDrawableCached() { return icon != null; }
    @Override void setDrawableCache(Drawable drawable) { icon = drawable; }

    @Override
    public Drawable getDrawable(Context context) {
        if (icon == null) {
            synchronized (this) {
                if (icon == null) {
                    if (pojo.icon != null) icon = decodeBoundedContactPhoto(context, pojo.icon);
                    if (icon == null) icon = getThemedDrawable(context, pojo, R.drawable.ic_contact);
                }
            }
        }
        return icon;
    }

    /** Decode contact provider photos at launcher-card resolution instead of retaining source-sized bitmaps. */
    private Drawable decodeBoundedContactPhoto(Context context, Uri uri) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) return null;
            BitmapFactory.decodeStream(input, null, bounds);
        } catch (IOException | RuntimeException e) {
            Log.d(TAG, "Unable to inspect contact icon", e);
            return null;
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        while (longest / (sample * 2) >= MAX_CACHED_CONTACT_PHOTO_EDGE) sample *= 2;

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, sample);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) return null;
            decoded = BitmapFactory.decodeStream(input, null, options);
        } catch (IOException | RuntimeException e) {
            Log.d(TAG, "Unable to load contact icon", e);
            return null;
        }
        if (decoded == null) return null;

        int decodedLongest = Math.max(decoded.getWidth(), decoded.getHeight());
        Bitmap bounded = decoded;
        if (decodedLongest > MAX_CACHED_CONTACT_PHOTO_EDGE) {
            float scale = MAX_CACHED_CONTACT_PHOTO_EDGE / (float) decodedLongest;
            bounded = Bitmap.createScaledBitmap(decoded,
                    Math.max(1, Math.round(decoded.getWidth() * scale)),
                    Math.max(1, Math.round(decoded.getHeight() * scale)), true);
            if (bounded != decoded) decoded.recycle();
        }
        return new BitmapDrawable(context.getResources(), bounded);
    }

    @Override
    public void inflateFavorite(@NonNull Context context, @NonNull View favoriteView) {
        Drawable drawable = getDrawable(context);
        if (drawable != null) drawable = ShapedContactBadge.getShapedDrawable(context, drawable);
        ImageView favoriteImage = favoriteView.findViewById(R.id.favorite);
        favoriteImage.setImageDrawable(drawable);
        favoriteView.setContentDescription(pojo.getName());
    }

    private void launchContactView(Context context, View v) {
        Intent viewContact = new Intent(Intent.ACTION_VIEW);
        Uri contactUri = ContactsContract.Contacts.CONTENT_LOOKUP_URI;
        contactUri = Uri.withAppendedPath(contactUri, String.valueOf(pojo.lookupKey));
        contactUri = Uri.withAppendedPath(contactUri, String.valueOf(pojo.getContactId()));
        viewContact.setData(contactUri);
        setSourceBounds(viewContact, v);
        viewContact.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        viewContact.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        context.startActivity(viewContact);
    }

    @Override
    public void doLaunch(Context context, View v) {
        if (pojo.getContactData() != null) {
            Intent exactContactIntent = MimeTypeUtils.getRegisteredIntentByMimeType(context,
                    pojo.getContactData().getMimeType(), pojo.getContactData().getId(),
                    pojo.getContactData().getIdentifier());
            if (exactContactIntent != null) {
                setSourceBounds(exactContactIntent, v);
                context.startActivity(exactContactIntent);
                return;
            }
        }
        SharedPreferences settingPrefs = PreferenceManager.getDefaultSharedPreferences(v.getContext());
        boolean callContactOnClick = settingPrefs.getBoolean("call-contact-on-click", false);
        if (callContactOnClick) launchCall(context, v, pojo.phone);
        else launchContactView(context, v);
    }

    private void launchMessaging(final Context context, final View view) {
        String url = "sms:" + Uri.encode(pojo.phone);
        Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse(url));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        setSourceBounds(intent, view);
        context.startActivity(intent);
    }

    private void launchIm(final Context context, final View view) {
        Intent intent = MimeTypeUtils.getRegisteredIntentByMimeType(context,
                pojo.getContactData().getMimeType(), pojo.getContactData().getId(),
                pojo.getContactData().getIdentifier());
        if (intent != null) {
            setSourceBounds(intent, view);
            context.startActivity(intent);
        }
    }

    @Override protected boolean isAllowedAsFavorite() { return true; }
    @Override protected boolean canRemoveFromHistory(Context context) { return true; }

    @Override
    protected boolean canHaveCustomIcon(Context context, IconPack iconPack) {
        return pojo.icon == null;
    }
}
