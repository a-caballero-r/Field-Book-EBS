package com.fieldbook.tracker.activities;

import android.Manifest;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.database.Cursor;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.location.Location;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout.LayoutParams;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import com.fieldbook.tracker.R;
import com.fieldbook.tracker.adapters.FieldAdapter;
import com.fieldbook.tracker.async.ImportRunnableTask;
import com.fieldbook.tracker.database.DataHelper;
import com.fieldbook.tracker.database.dao.ObservationUnitDao;
import com.fieldbook.tracker.database.models.ObservationUnitModel;
import com.fieldbook.tracker.dialogs.FieldCreatorDialog;
import com.fieldbook.tracker.location.GPSTracker;
import com.fieldbook.tracker.objects.FieldFileObject;
import com.fieldbook.tracker.objects.FieldObject;
import com.fieldbook.tracker.preferences.GeneralKeys;
import com.fieldbook.tracker.utilities.DialogUtils;
import com.fieldbook.tracker.utilities.DocumentTreeUtil;
import com.fieldbook.tracker.utilities.Utils;
import com.getkeepsafe.taptargetview.TapTarget;
import com.getkeepsafe.taptargetview.TapTargetSequence;
import com.google.android.material.snackbar.Snackbar;

import org.phenoapps.utils.BaseDocumentTreeUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.EasyPermissions;

public class FieldEditorActivity extends AppCompatActivity {

    private static final int REQUEST_FILE_EXPLORER_CODE = 1;
    private static final int REQUEST_CLOUD_FILE_CODE = 5;

    private static final int DIALOG_LOAD_FIELDFILECSV = 1000;
    private static final int DIALOG_LOAD_FIELDFILEEXCEL = 1001;
    public static ListView fieldList;
    public static FieldAdapter mAdapter;
    public static AppCompatActivity thisActivity;
    public static EditText trait;
    private static Handler mHandler = new Handler();
    private static FieldFileObject.FieldFileBase fieldFile;
    private static SharedPreferences ep;
    private final int PERMISSIONS_REQUEST_STORAGE = 998;
    Spinner unique;
    Spinner primary;
    Spinner secondary;
    private Menu systemMenu;

    private GPSTracker mGpsTracker;

    // Creates a new thread to do importing
    private final Runnable importRunnable = new Runnable() {
        public void run() {
            new ImportRunnableTask(thisActivity,
                    fieldFile,
                    unique.getSelectedItemPosition(),
                    unique.getSelectedItem().toString(),
                    primary.getSelectedItem().toString(),
                    secondary.getSelectedItem().toString()).execute(0);
        }
    };

    // Helper function to load data
    public static void loadData() {
        try {
            ConfigActivity.dt.open();
            mAdapter = new FieldAdapter(thisActivity, ConfigActivity.dt.getAllFieldObjects());
            fieldList.setAdapter(mAdapter);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void scanFile(File filePath) {
        MediaScannerConnection.scanFile(thisActivity, new String[]{filePath.getAbsolutePath()}, null, null);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (systemMenu != null) {
            systemMenu.findItem(R.id.help).setVisible(ep.getBoolean(GeneralKeys.TIPS, false));
        }
        loadData();

        mGpsTracker = new GPSTracker(this);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ep = getSharedPreferences(GeneralKeys.SHARED_PREF_FILE_NAME, 0);

        setContentView(R.layout.activity_fields);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(getString(R.string.settings_fields));
            getSupportActionBar().getThemedContext();
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeButtonEnabled(true);
        }

        thisActivity = this;
        if (ConfigActivity.dt == null) {    // when resuming
            ConfigActivity.dt = new DataHelper(this);
        }
        ConfigActivity.dt.open();
        ConfigActivity.dt.updateExpTable(false, true, false, ep.getInt(GeneralKeys.SELECTED_FIELD_ID, 0));
        fieldList = findViewById(R.id.myList);
        mAdapter = new FieldAdapter(thisActivity, ConfigActivity.dt.getAllFieldObjects());
        fieldList.setAdapter(mAdapter);
    }

    private void showFileDialog() {
        LayoutInflater inflater = this.getLayoutInflater();
        View layout = inflater.inflate(R.layout.dialog_list_buttonless, null);

        ListView importSourceList = layout.findViewById(R.id.myList);
        String[] importArray = new String[3];
        importArray[0] = getString(R.string.import_source_local);
        importArray[1] = getString(R.string.import_source_cloud);
        importArray[2] = getString(R.string.import_source_brapi);


        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.listitem, importArray);
        importSourceList.setAdapter(adapter);

        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AppAlertDialog);
        builder.setTitle(R.string.import_dialog_title_fields)
                .setCancelable(true)
                .setView(layout);

        builder.setPositiveButton(getString(R.string.dialog_cancel), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {

            }
        });

        final AlertDialog importDialog = builder.create();
        importDialog.show();
        DialogUtils.styleDialogs(importDialog);

        android.view.WindowManager.LayoutParams params = importDialog.getWindow().getAttributes();
        params.width = LayoutParams.MATCH_PARENT;
        params.height = LayoutParams.WRAP_CONTENT;
        importDialog.getWindow().setAttributes(params);

        importSourceList.setOnItemClickListener((av, arg1, which, arg3) -> {
            switch (which) {
                case 0:
                    loadLocalPermission();
                    break;
                case 1:
                    loadCloud();
                    break;
                case 2:
                    loadBrAPI();
                    break;

            }
            importDialog.dismiss();
        });
    }

    public void loadLocal() {

        try {
            DocumentFile importDir = BaseDocumentTreeUtil.Companion.getDirectory(this, R.string.dir_field_import);
            if (importDir != null && importDir.exists()) {
                Intent intent = new Intent();
                intent.setClassName(FieldEditorActivity.this, FileExploreActivity.class.getName());
                intent.putExtra("path", importDir.getUri().toString());
                intent.putExtra("include", new String[]{"csv", "xls", "xlsx"});
                intent.putExtra("title", getString(R.string.import_dialog_title_fields));
                startActivityForResult(intent, REQUEST_FILE_EXPLORER_CODE);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void loadBrAPI() {
        Intent intent = new Intent();

        intent.setClassName(FieldEditorActivity.this,
                BrapiActivity.class.getName());
        startActivityForResult(intent, 1);
    }

    public void loadCloud() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        try {
            startActivityForResult(Intent.createChooser(intent, "cloudFile"), REQUEST_CLOUD_FILE_CODE);
        } catch (android.content.ActivityNotFoundException ex) {
            Toast.makeText(getApplicationContext(), "No suitable File Manager was found.", Toast.LENGTH_SHORT).show();
        }
    }

    @AfterPermissionGranted(PERMISSIONS_REQUEST_STORAGE)
    public void loadLocalPermission() {
        String[] perms = {Manifest.permission.READ_EXTERNAL_STORAGE};
        if (EasyPermissions.hasPermissions(this, perms)) {
            loadLocal();
        } else {
            // Do not have permissions, request them now
            EasyPermissions.requestPermissions(this, getString(R.string.permission_rationale_storage_import),
                    PERMISSIONS_REQUEST_STORAGE, perms);
        }

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        new MenuInflater(FieldEditorActivity.this).inflate(R.menu.menu_fields, menu);

        systemMenu = menu;
        systemMenu.findItem(R.id.help).setVisible(ep.getBoolean(GeneralKeys.TIPS, false));

        return true;
    }

    private Rect fieldsListItemLocation(int item) {
        View v = fieldList.getChildAt(item);
        final int[] location = new int[2];
        v.getLocationOnScreen(location);
        Rect droidTarget = new Rect(location[0], location[1], location[0] + v.getWidth() / 5, location[1] + v.getHeight());
        return droidTarget;
    }

    private TapTarget fieldsTapTargetRect(Rect item, String title, String desc) {
        return TapTarget.forBounds(item, title, desc)
                // All options below are optional
                .outerCircleColor(R.color.main_primaryDark)      // Specify a color for the outer circle
                .outerCircleAlpha(0.95f)            // Specify the alpha amount for the outer circle
                .targetCircleColor(R.color.black)   // Specify a color for the target circle
                .titleTextSize(30)                  // Specify the size (in sp) of the title text
                .descriptionTextSize(20)            // Specify the size (in sp) of the description text
                .descriptionTextColor(R.color.black)  // Specify the color of the description text
                .descriptionTypeface(Typeface.DEFAULT_BOLD)
                .textColor(R.color.black)            // Specify a color for both the title and description text
                .dimColor(R.color.black)            // If set, will dim behind the view with 30% opacity of the given color
                .drawShadow(true)                   // Whether to draw a drop shadow or not
                .cancelable(false)                  // Whether tapping outside the outer circle dismisses the view
                .tintTarget(true)                   // Whether to tint the target view's color
                .transparentTarget(true)           // Specify whether the target is transparent (displays the content underneath)
                .targetRadius(60);
    }

    private TapTarget fieldsTapTargetMenu(int id, String title, String desc) {
        return TapTarget.forView(findViewById(id), title, desc)
                // All options below are optional
                .outerCircleColor(R.color.main_primaryDark)      // Specify a color for the outer circle
                .outerCircleAlpha(0.95f)            // Specify the alpha amount for the outer circle
                .targetCircleColor(R.color.black)   // Specify a color for the target circle
                .titleTextSize(30)                  // Specify the size (in sp) of the title text
                .descriptionTextSize(20)            // Specify the size (in sp) of the description text
                .descriptionTextColor(R.color.black)  // Specify the color of the description text
                .descriptionTypeface(Typeface.DEFAULT_BOLD)
                .textColor(R.color.black)            // Specify a color for both the title and description text
                .dimColor(R.color.black)            // If set, will dim behind the view with 30% opacity of the given color
                .drawShadow(true)                   // Whether to draw a drop shadow or not
                .cancelable(false)                  // Whether tapping outside the outer circle dismisses the view
                .tintTarget(true)                   // Whether to tint the target view's color
                .transparentTarget(true)           // Specify whether the target is transparent (displays the content underneath)
                .targetRadius(60);
    }

    //TODO
    private Boolean fieldExists() {

        return false;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.help:
                TapTargetSequence sequence = new TapTargetSequence(this)
                        .targets(fieldsTapTargetMenu(R.id.importField, getString(R.string.tutorial_fields_add_title), getString(R.string.tutorial_fields_add_description)),
                                fieldsTapTargetMenu(R.id.importField, getString(R.string.tutorial_fields_add_title), getString(R.string.tutorial_fields_file_description))
                        );

                if (fieldExists()) {
                    sequence.target(fieldsTapTargetRect(fieldsListItemLocation(0), getString(R.string.tutorial_fields_select_title), getString(R.string.tutorial_fields_select_description)));
                    sequence.target(fieldsTapTargetRect(fieldsListItemLocation(0), getString(R.string.tutorial_fields_delete_title), getString(R.string.tutorial_fields_delete_description)));
                }

                sequence.start();

                break;

            case R.id.importField:
                String importer = ep.getString("IMPORT_SOURCE_DEFAULT", "ask");

                switch (importer) {
                    case "ask":
                        showFileDialog();
                        break;
                    case "local":
                        loadLocal();
                        break;
                    case "brapi":
                        loadBrAPI();
                        break;
                    case "cloud":
                        loadCloud();
                        break;
                    default:
                        showFileDialog();
                }
                break;

            case R.id.menu_field_editor_item_creator:

                FieldCreatorDialog dialog = new FieldCreatorDialog(this);

                //when the dialog is dismissed, the field data is created or failed
                dialog.setOnDismissListener((dismiss -> {

                    //update list of fields
                    fieldList = findViewById(R.id.myList);
                    mAdapter = new FieldAdapter(thisActivity, ConfigActivity.dt.getAllFieldObjects());
                    fieldList.setAdapter(mAdapter);

                }));

                dialog.show();

                break;

            case android.R.id.home:
                CollectActivity.reloadData = true;
                finish();
                break;

            case R.id.action_select_plot_by_distance:

                if (mGpsTracker != null && mGpsTracker.canGetLocation()) {

                    selectPlotByDistance();

                } else {

                    Toast.makeText(this, R.string.activity_field_editor_no_location_yet, Toast.LENGTH_SHORT).show();

                }

                break;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Programmatically selects the closest field to the user's location.
     * Finds all observation units with geocoordinate data, sorts the list and finds the first item.
     */
    private void selectPlotByDistance() {

        if (mGpsTracker != null && mGpsTracker.canGetLocation()) {

            //get current coordinate of the user
            Location thisLocation = mGpsTracker.getLocation();

            ObservationUnitModel[] units = ObservationUnitDao.Companion.getAll();
            List<ObservationUnitModel> coordinates = new ArrayList<>();

            //find all observation units with a coordinate
            for (ObservationUnitModel model : units) {
                String latlng = model.getGeo_coordinates();
                if (latlng != null && !latlng.isEmpty()) {
                    coordinates.add(model);
                }
            }

            //sort the coordinates based on the distance from the user
            Collections.sort(coordinates, (a, b) -> {
                double distanceA, distanceB;
                Location locationA = a.getLocation();
                Location locationB = b.getLocation();
                if (locationA == null) distanceA = Double.MAX_VALUE;
                else distanceA = thisLocation.distanceTo(locationA);
                if (locationB == null) distanceB = Double.MAX_VALUE;
                else distanceB = thisLocation.distanceTo(locationB);
                return Double.compare(distanceA, distanceB);
            });

            Optional<ObservationUnitModel> closest = coordinates.stream().findFirst();

            try {

                if (closest.isPresent()) {

                    ObservationUnitModel model = closest.get();

                    int studyId = model.getStudy_id();

                    if (studyId == ep.getInt(GeneralKeys.SELECTED_FIELD_ID, -1)) {

                        Snackbar.make(findViewById(R.id.field_editor_parent_linear_layout),
                                getString(R.string.activity_field_editor_switch_field_same),
                                Snackbar.LENGTH_LONG).show();

                    } else {

                        Snackbar mySnackbar = Snackbar.make(findViewById(R.id.field_editor_parent_linear_layout),
                                getString(R.string.activity_field_editor_switch_field, String.valueOf(studyId)),
                                Snackbar.LENGTH_LONG);

                        mySnackbar.setAction(R.string.activity_field_editor_switch_field_action, (view) -> {

                            int count = mAdapter.getCount();

                            for (int i = 0; i < count; i++) {
                                FieldObject field = mAdapter.getItem(i);
                                if (field.getExp_id() == studyId) {
                                    mAdapter.getView(i, null, null).performClick();
                                }
                            }

                        });

                        mySnackbar.show();
                    }
                }

            } catch (NoSuchElementException e) {

                Toast.makeText(this, R.string.activity_field_editor_no_field_found, Toast.LENGTH_SHORT).show();
            }
        } else {

            Toast.makeText(this, R.string.activity_field_editor_no_location_yet, Toast.LENGTH_SHORT).show();

        }
    }

    public String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index > -1) {
                        result = cursor.getString(index);
                    }
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }

    public void onBackPressed() {
        CollectActivity.reloadData = true;
        finish();
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == 2) {
            if (resultCode == RESULT_OK) {
                final String chosenFile = data.getStringExtra("result");
                showFieldFileDialog(chosenFile, null);
            }
        }

        if (requestCode == REQUEST_FILE_EXPLORER_CODE) {
            if (resultCode == RESULT_OK) {
                final String chosenFile = data.getStringExtra(FileExploreActivity.EXTRA_RESULT_KEY);
                showFieldFileDialog(chosenFile, null);
            }
        }

        if (requestCode == REQUEST_CLOUD_FILE_CODE && resultCode == RESULT_OK && data.getData() != null) {
            Uri content_describer = data.getData();
            final String chosenFile = getFileName(content_describer);
            if (chosenFile != null) {
                InputStream in = null;
                OutputStream out = null;
                try {
                    in = getContentResolver().openInputStream(content_describer);
                    out = BaseDocumentTreeUtil.Companion.getFileOutputStream(this,
                            R.string.dir_field_import, chosenFile);
                    if (out == null) throw new IOException();
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    if (in != null) {
                        try {
                            in.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    if (out != null){
                        try {
                            out.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }

                String extension = FieldFileObject.getExtension(chosenFile);

                if(!extension.equals("csv") && !extension.equals("xls") && !extension.equals("xlsx")) {
                    Toast.makeText(FieldEditorActivity.thisActivity, getString(R.string.import_error_format_field), Toast.LENGTH_LONG).show();
                    return;
                }

                showFieldFileDialog(content_describer.toString(), true);
            }
        }
    }

    private void showFieldFileDialog(final String chosenFile, Boolean isCloud) {

        try {

            Uri docUri = Uri.parse(chosenFile);

            DocumentFile importDoc = DocumentFile.fromSingleUri(this, docUri);

            if (importDoc != null && importDoc.exists()) {

                ContentResolver resolver = getContentResolver();
                if (resolver != null) {

                    String cloudName = null;
                    if (isCloud != null && isCloud) {
                        cloudName = getFileName(Uri.parse(chosenFile));
                    }

                    try(InputStream is = resolver.openInputStream(docUri)) {

                        fieldFile = FieldFileObject.create(this, docUri, is, cloudName);

                        String fieldFileName = fieldFile.getStem();

                        Editor e = ep.edit();
                        e.putString(GeneralKeys.FIELD_FILE, fieldFileName);
                        e.apply();

                        if (ConfigActivity.dt.checkFieldName(fieldFileName) >= 0) {
                            Utils.makeToast(getApplicationContext(),getString(R.string.fields_study_exists_message));
                            SharedPreferences.Editor ed = ep.edit();
                            ed.putString(GeneralKeys.FIELD_FILE, null);
                            ed.putBoolean(GeneralKeys.IMPORT_FIELD_FINISHED, false);
                            ed.apply();
                            return;
                        }

                        if (fieldFile.isOther()) {
                            Utils.makeToast(getApplicationContext(),getString(R.string.import_error_unsupported));
                        }

                        //utility call creates photos, audio and thumbnails folders under a new field folder
                        DocumentTreeUtil.Companion.createFieldDir(this, fieldFileName);

                        loadFile(fieldFile);

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }

        } catch (Exception e) {

            Utils.makeToast(this, getString(R.string.act_field_editor_load_file_failed));

            e.printStackTrace();
        }
    }

    /**
     * The user selects between the columns in fieldFile to determine the primary/secondary/unique ids
     * These ids are used to navigate between plots in the collect activity.
     * Sanitization has to happen here to ensure no empty string column is selected.
     * Also special characters are checked for and replaced here, if they exist a message is shown to the user.
     * @param fieldFile contains the parsed input file which has columns
     */
    private void loadFile(FieldFileObject.FieldFileBase fieldFile) {

        String[] importColumns = fieldFile.getColumns();

        if (fieldFile.getOpenFailed()) {
            Utils.makeToast(this, getString(R.string.act_field_editor_file_open_failed));
            return;
        }

        //in some cases getColumns is returning null, so print an error message to the user
        if (importColumns != null) {

            //only reserved word for now is id which is used in many queries
            //other sqlite keywords are sanitized with a tick mark to make them an identifier
            String[] reservedNames = new String[]{"id"};

            //replace specials and emptys and add them to the actual columns list to be displayed
            ArrayList<String> actualColumns = new ArrayList<>();

            List<String> list = Arrays.asList(reservedNames);

            //define flag to let the user know characters were replaced at the end of the loop
            boolean hasSpecialCharacters = false;
            for (String s : importColumns) {

                boolean added = false;

                //replace the special characters, only add to the actual list if it is not empty
                if (DataHelper.hasSpecialChars(s)) {

                    hasSpecialCharacters = true;
                    added = true;
                    String replaced = DataHelper.replaceSpecialChars(s);
                    if (!replaced.isEmpty() && !actualColumns.contains(replaced))
                        actualColumns.add(replaced);

                }

                if (list.contains(s.toLowerCase())) {

                    Utils.makeToast(getApplicationContext(), getString(R.string.import_error_column_name) + " \"" + s + "\"");

                    return;
                }

                if (!added) {

                    if (!s.isEmpty()) actualColumns.add(s);

                }

            }

            if (actualColumns.size() > 0) {

                if (hasSpecialCharacters) {


                    Utils.makeToast(getApplicationContext(),getString(R.string.import_error_columns_replaced));

                }

                importDialog(actualColumns.toArray(new String[] {}));

            } else {

                Toast.makeText(this, R.string.act_field_editor_no_suitable_columns_error,
                        Toast.LENGTH_SHORT).show();
            }
        } else {

            Utils.makeToast(getApplicationContext(), getString(R.string.act_field_editor_failed_to_read_columns));
        }
    }

    private void importDialog(String[] columns) {
        LayoutInflater inflater = this.getLayoutInflater();
        View layout = inflater.inflate(R.layout.dialog_import, null);

        unique = layout.findViewById(R.id.uniqueSpin);
        primary = layout.findViewById(R.id.primarySpin);
        secondary = layout.findViewById(R.id.secondarySpin);

        setSpinner(unique, columns, GeneralKeys.UNIQUE_NAME);
        setSpinner(primary, columns, GeneralKeys.PRIMARY_NAME);
        setSpinner(secondary, columns, GeneralKeys.SECONDARY_NAME);

        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AppAlertDialog);
        builder.setTitle(R.string.import_dialog_title_fields)
                .setCancelable(true)
                .setView(layout);

        builder.setPositiveButton(getString(R.string.dialog_import), (dialogInterface, i) -> {
            if (checkImportColumnNames()) {
                mHandler.post(importRunnable);
            }
        });

        AlertDialog importFieldDialog = builder.create();
        importFieldDialog.show();
        DialogUtils.styleDialogs(importFieldDialog);

        android.view.WindowManager.LayoutParams params2 = importFieldDialog.getWindow().getAttributes();
        params2.width = LayoutParams.MATCH_PARENT;
        importFieldDialog.getWindow().setAttributes(params2);
    }

    // Helper function to set spinner adapter and listener
    private void setSpinner(Spinner spinner, String[] data, String pref) {
        ArrayAdapter<String> itemsAdapter = new ArrayAdapter<>(this, R.layout.custom_spinnerlayout, data);
        spinner.setAdapter(itemsAdapter);
        int spinnerPosition = itemsAdapter.getPosition(ep.getString(pref, itemsAdapter.getItem(0)));
        spinner.setSelection(spinnerPosition);
    }

    // Validate that column choices are different from one another
    private boolean checkImportColumnNames() {
        final String uniqueS = unique.getSelectedItem().toString();
        final String primaryS = primary.getSelectedItem().toString();
        final String secondaryS = secondary.getSelectedItem().toString();

        if (uniqueS.equals(primaryS) || uniqueS.equals(secondaryS) || primaryS.equals(secondaryS)) {
            Utils.makeToast(getApplicationContext(),getString(R.string.import_error_column_choice));
            return false;
        }

        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // Forward results to EasyPermissions
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }
}