package de.pixart.messenger.ui;

import android.app.Activity;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;

import de.pixart.messenger.Config;

public abstract class LocationActivity extends Activity implements LocationListener {
    private LocationManager locationManager;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
    }

    protected abstract void gotoLoc() throws UnsupportedOperationException;

    protected abstract void setmLastLocation(final Location location);

    protected void requestLocationUpdates() {
        final Location lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (lastKnownLocation != null) {
            setmLastLocation(lastKnownLocation);
            try {
                gotoLoc();
            } catch (final UnsupportedOperationException ignored) {
            }
        }
        if (locationManager.getAllProviders().contains(LocationManager.NETWORK_PROVIDER)
                && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, Config.LOCATION_FIX_TIME_DELTA, Config.LOCATION_FIX_SPACE_DELTA, this);
        }
        if (locationManager.getAllProviders().contains(LocationManager.GPS_PROVIDER)
                && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, Config.LOCATION_FIX_TIME_DELTA, Config.LOCATION_FIX_SPACE_DELTA, this);
        }
        // If something else is also querying for location more frequently than we are, the battery is already being
        // drained. Go ahead and use the existing locations as often as we can get them.
        if (locationManager.getAllProviders().contains(LocationManager.PASSIVE_PROVIDER)) {
            locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0, 0, this);
        }
    }

    protected void pauseLocationUpdates() {
        locationManager.removeUpdates(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        pauseLocationUpdates();
    }

    @Override
    protected void onResume() {
        super.onResume();
        this.setmLastLocation(null);

        requestLocationUpdates();
    }
}