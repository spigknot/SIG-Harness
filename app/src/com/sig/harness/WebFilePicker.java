package com.sig.harness;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient.FileChooserParams;
import android.webkit.WebView;

import java.util.ArrayList;
import java.util.List;

/** Routes the Android picker result to the WebView that requested it. */
final class WebFilePicker {
    private static final int REQUEST = 4401;
    private final Activity activity;
    private boolean picking;
    private WebView owner;
    private ValueCallback<Uri[]> callback;

    WebFilePicker(Activity activity) { this.activity = activity; }

    boolean open(WebView view, ValueCallback<Uri[]> result, FileChooserParams params) {
        if (picking) {
            result.onReceiveValue(null);
            return true;
        }
        List<String> types = new ArrayList<>();
        if (params != null && params.getAcceptTypes() != null) {
            for (String type : params.getAcceptTypes()) {
                if (type != null && !type.trim().isEmpty()) types.add(type.trim());
            }
        }
        boolean multiple = params != null && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;
        boolean imagesOnly = !types.isEmpty();
        for (String type : types) imagesOnly &= type.startsWith("image/");

        boolean launched = false;
        if (imagesOnly && Build.VERSION.SDK_INT >= 33) {
            Intent photo = new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*");
            if (multiple) photo.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 10);
            launched = launch(photo);
        }
        if (!launched) {
            Intent files = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(types.size() == 1 ? types.get(0) : "*/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
            if (types.size() > 1) files.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[0]));
            launched = launch(files);
        }
        if (launched) {
            picking = true;
            owner = view;
            callback = result;
        } else {
            result.onReceiveValue(null);
        }
        return true;
    }

    private boolean launch(Intent intent) {
        try {
            activity.startActivityForResult(intent, REQUEST);
            return true;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            return false;
        }
    }

    void cancel(WebView view) {
        if (owner != view) return;
        ValueCallback<Uri[]> result = callback;
        owner = null;
        callback = null;
        if (result != null) result.onReceiveValue(null);
    }

    boolean handleResult(int request, int code, Intent data) {
        if (request != REQUEST) return false;
        ValueCallback<Uri[]> result = callback;
        picking = false;
        callback = null;
        owner = null;
        if (result == null) return true;

        List<Uri> uris = new ArrayList<>();
        if (code == Activity.RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) addContent(uris, clip.getItemAt(i).getUri());
            } else {
                addContent(uris, data.getData());
            }
        }
        result.onReceiveValue(uris.isEmpty() ? null : uris.toArray(new Uri[0]));
        return true;
    }

    private static void addContent(List<Uri> uris, Uri uri) {
        if (uri != null && "content".equals(uri.getScheme())) uris.add(uri);
    }
}
