package com.titanpointer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile that switches pointer mode on and off. */
public final class PointerTileService extends TileService {

    public static void requestUpdate(Context c) {
        try {
            requestListeningState(c, new ComponentName(c, PointerTileService.class));
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        if (!PointerService.isRunning()) {
            Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 0, i,
                        android.app.PendingIntent.FLAG_IMMUTABLE));
            } else {
                startLegacy(i);
            }
            return;
        }
        boolean on = !Prefs.get(this).getBoolean(Prefs.ENABLED, true);
        Prefs.get(this).edit().putBoolean(Prefs.ENABLED, on).apply();
        refresh();
    }

    @SuppressWarnings("deprecation")
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private void startLegacy(Intent i) {
        startActivityAndCollapse(i);
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean on = PointerService.isRunning() && Prefs.get(this).getBoolean(Prefs.ENABLED, true);
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE); // unavailable tiles can't be tapped
        t.updateTile();
    }
}
