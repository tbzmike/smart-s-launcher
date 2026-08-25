package fr.neamar.kiss.forwarder;

import java.util.List;

/** List order is persisted from back to front and mirrors FrameLayout child order. */
final class WidgetLayerOrder {
    private WidgetLayerOrder() { }

    static <T> boolean bringToFront(List<T> order, T item) {
        int index = order.indexOf(item);
        if (index < 0 || index == order.size() - 1) return false;
        order.remove(index);
        order.add(item);
        return true;
    }

    static <T> boolean sendToBack(List<T> order, T item) {
        int index = order.indexOf(item);
        if (index <= 0) return false;
        order.remove(index);
        order.add(0, item);
        return true;
    }
}
