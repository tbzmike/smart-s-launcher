package fr.neamar.kiss.shortcut;

import android.content.Intent;
import android.os.Bundle;
import android.os.PersistableBundle;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.lang.reflect.Array;

/** Portable exact Intent stacks, including the typed extras that Intent.toUri omits. */
@androidx.annotation.RequiresApi(26)
public final class ShortcutRouteCodec {
    private ShortcutRouteCodec() { }

    public static JSONArray encode(Intent[] intents) throws JSONException {
        if (intents == null || intents.length == 0 || intents.length > 16) {
            throw new JSONException("Invalid shortcut Intent stack");
        }
        JSONArray result = new JSONArray();
        for (Intent intent : intents) {
            result.put(new JSONObject().put("uri", intent.toUri(Intent.URI_INTENT_SCHEME))
                    .put("extras", encodeBundle(intent.getExtras())));
        }
        return result;
    }

    public static Intent[] decode(JSONArray array) throws Exception {
        if (array.length() == 0 || array.length() > 16) throw new JSONException("Invalid route");
        Intent[] result = new Intent[array.length()];
        for (int i = 0; i < result.length; i++) {
            JSONObject item = array.getJSONObject(i);
            result[i] = Intent.parseUri(item.getString("uri"), Intent.URI_INTENT_SCHEME);
            result[i].replaceExtras(decodeBundle(item.getJSONObject("extras")));
        }
        return result;
    }

    private static JSONObject encodeBundle(Bundle bundle) throws JSONException {
        JSONObject result = new JSONObject();
        if (bundle != null) for (String key : bundle.keySet()) result.put(key, encodeValue(bundle.get(key)));
        return result;
    }

    private static JSONObject encodeValue(Object value) throws JSONException {
        JSONObject out = new JSONObject();
        if (value == null) return out.put("type", "null");
        String type = value.getClass().getSimpleName();
        out.put("type", type);
        if (value instanceof Bundle) return out.put("value", encodeBundle((Bundle) value));
        if (value instanceof PersistableBundle) {
            PersistableBundle bundle = (PersistableBundle) value;
            JSONObject items = new JSONObject();
            for (String key : bundle.keySet()) items.put(key, encodeValue(bundle.get(key)));
            return out.put("value", items);
        }
        if (value.getClass().isArray()) {
            JSONArray array = new JSONArray();
            for (int i = 0; i < Array.getLength(value); i++) array.put(Array.get(value, i));
            return out.put("value", array);
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return out.put("value", value);
        }
        if (value instanceof Character) return out.put("value", value.toString());
        throw new JSONException("Unsupported shortcut extra " + type);
    }

    private static Bundle decodeBundle(JSONObject source) throws JSONException {
        Bundle result = new Bundle();
        java.util.Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject item = source.getJSONObject(key);
            String type = item.getString("type");
            switch (type) {
                case "null": result.putString(key, null); break;
                case "String": result.putString(key, item.getString("value")); break;
                case "Integer": result.putInt(key, item.getInt("value")); break;
                case "Long": result.putLong(key, item.getLong("value")); break;
                case "Double": result.putDouble(key, item.getDouble("value")); break;
                case "Float": result.putFloat(key, (float) item.getDouble("value")); break;
                case "Boolean": result.putBoolean(key, item.getBoolean("value")); break;
                case "Short": result.putShort(key, (short) item.getInt("value")); break;
                case "Byte": result.putByte(key, (byte) item.getInt("value")); break;
                case "Character": result.putChar(key, item.getString("value").charAt(0)); break;
                case "Bundle": result.putBundle(key, decodeBundle(item.getJSONObject("value"))); break;
                case "PersistableBundle": result.putParcelable(key,
                        persistable(decodeBundle(item.getJSONObject("value")))); break;
                default:
                    JSONArray values = item.getJSONArray("value");
                    Object array;
                    switch (type) {
                        case "String[]": array = new String[values.length()]; break;
                        case "int[]": array = new int[values.length()]; break;
                        case "long[]": array = new long[values.length()]; break;
                        case "double[]": array = new double[values.length()]; break;
                        case "float[]": array = new float[values.length()]; break;
                        case "boolean[]": array = new boolean[values.length()]; break;
                        case "byte[]": array = new byte[values.length()]; break;
                        case "short[]": array = new short[values.length()]; break;
                        case "char[]": array = new char[values.length()]; break;
                        default: throw new JSONException("Unsupported route extra " + type);
                    }
                    for (int i = 0; i < values.length(); i++) {
                        switch (type) {
                            case "String[]": ((String[]) array)[i] = values.isNull(i) ? null : values.getString(i); break;
                            case "int[]": ((int[]) array)[i] = values.getInt(i); break;
                            case "long[]": ((long[]) array)[i] = values.getLong(i); break;
                            case "double[]": ((double[]) array)[i] = values.getDouble(i); break;
                            case "float[]": ((float[]) array)[i] = (float) values.getDouble(i); break;
                            case "boolean[]": ((boolean[]) array)[i] = values.getBoolean(i); break;
                            case "byte[]": ((byte[]) array)[i] = (byte) values.getInt(i); break;
                            case "short[]": ((short[]) array)[i] = (short) values.getInt(i); break;
                            case "char[]": ((char[]) array)[i] = values.getString(i).charAt(0); break;
                        }
                    }
                    if (array instanceof String[]) result.putStringArray(key, (String[]) array);
                    else if (array instanceof int[]) result.putIntArray(key, (int[]) array);
                    else if (array instanceof long[]) result.putLongArray(key, (long[]) array);
                    else if (array instanceof double[]) result.putDoubleArray(key, (double[]) array);
                    else if (array instanceof float[]) result.putFloatArray(key, (float[]) array);
                    else if (array instanceof boolean[]) result.putBooleanArray(key, (boolean[]) array);
                    else if (array instanceof byte[]) result.putByteArray(key, (byte[]) array);
                    else if (array instanceof short[]) result.putShortArray(key, (short[]) array);
                    else if (array instanceof char[]) result.putCharArray(key, (char[]) array);
            }
        }
        return result;
    }

    private static PersistableBundle persistable(Bundle source) throws JSONException {
        PersistableBundle result = new PersistableBundle();
        for (String key : source.keySet()) {
            Object value = source.get(key);
            if (value == null || value instanceof String) result.putString(key, (String) value);
            else if (value instanceof Integer) result.putInt(key, (Integer) value);
            else if (value instanceof Long) result.putLong(key, (Long) value);
            else if (value instanceof Double) result.putDouble(key, (Double) value);
            else if (value instanceof Boolean) result.putBoolean(key, (Boolean) value);
            else if (value instanceof String[]) result.putStringArray(key, (String[]) value);
            else if (value instanceof int[]) result.putIntArray(key, (int[]) value);
            else if (value instanceof long[]) result.putLongArray(key, (long[]) value);
            else if (value instanceof double[]) result.putDoubleArray(key, (double[]) value);
            else if (value instanceof boolean[]) result.putBooleanArray(key, (boolean[]) value);
            else if (value instanceof PersistableBundle) result.putPersistableBundle(key, (PersistableBundle) value);
            else throw new JSONException("Unsupported persistable route extra");
        }
        return result;
    }
}
