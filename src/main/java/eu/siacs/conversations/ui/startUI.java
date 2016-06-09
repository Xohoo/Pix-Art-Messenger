package eu.siacs.conversations.ui;

import android.Manifest;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;

import java.util.Arrays;
import java.util.List;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.EasyPermissions;

/**
 * Created by Christian on 10.06.2016.
 */
public class startUI extends AppCompatActivity
        implements EasyPermissions.PermissionCallbacks {

    private static final int NeededPermissions = 1000;

    String[] perms = {Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_start_ui);
        requestNeededPermissions();
    }

    @AfterPermissionGranted(NeededPermissions)
    private void requestNeededPermissions() {
        if (EasyPermissions.hasPermissions(this, perms)) {
            // Already have permission, start ConversationsActivity
            startActivity(new Intent(this, ConversationActivity.class));
            finish();
        } else {
            // Do not have permissions, request them now
            EasyPermissions.requestPermissions(this, getString(R.string.request_permissions_message),
                    NeededPermissions, perms);
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Forward results to EasyPermissions
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }

    @Override
    public void onPermissionsGranted(int requestCode, List<String> list) {
        Log.d(Config.LOGTAG, "Permissions granted:" + requestCode);
    }

    @Override
    public void onPermissionsDenied(int requestCode, List<String> list) {
        Log.d(Config.LOGTAG, "Permissions denied:" + requestCode);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setMessage(getString(R.string.request_permissions_message_again))
                .setPositiveButton(getString(R.string.yes), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        Uri uri = Uri.fromParts("package", getPackageName(), null);
                        intent.setData(uri);
                        startActivity(intent);
                    }
                })
                .setNegativeButton(getString(R.string.no), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .create();
        dialog.show();
    }

    private void restart() {
        //restart app
        Log.d(Config.LOGTAG, "Restarting " + getBaseContext().getPackageManager().getLaunchIntentForPackage(getBaseContext().getPackageName()));
        Intent intent = getBaseContext().getPackageManager().getLaunchIntentForPackage(getBaseContext().getPackageName());
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        System.exit(0);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}