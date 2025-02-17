/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.keychain;

import android.annotation.NonNull;
import android.app.Activity;
import android.app.admin.IDevicePolicyManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.security.Credentials;
import android.security.IKeyChainAliasCallback;
import android.security.KeyChain;
import android.security.KeyStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.TextView;
import com.android.internal.annotations.VisibleForTesting;
import com.android.keychain.internal.KeyInfoProvider;
import com.android.org.bouncycastle.asn1.x509.X509Name;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.List;
import java.util.stream.Collectors;

import javax.security.auth.x500.X500Principal;

import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

public class KeyChainActivity extends Activity {
    private static final String TAG = "KeyChain";

    private static String KEY_STATE = "state";

    private static final int REQUEST_UNLOCK = 1;

    private int mSenderUid;

    private PendingIntent mSender;

    // beware that some of these KeyStore operations such as saw and
    // get do file I/O in the remote keystore process and while they
    // do not cause StrictMode violations, they logically should not
    // be done on the UI thread.
    private KeyStore mKeyStore = KeyStore.getInstance();

    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        getWindow().addSystemFlags(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
    }

    @Override public void onResume() {
        super.onResume();

        mSender = getIntent().getParcelableExtra(KeyChain.EXTRA_SENDER);
        if (mSender == null) {
            // if no sender, bail, we need to identify the app to the user securely.
            finish(null);
            return;
        }
        try {
            mSenderUid = getPackageManager().getPackageInfo(
                    mSender.getIntentSender().getTargetPackage(), 0).applicationInfo.uid;
        } catch (PackageManager.NameNotFoundException e) {
            // if unable to find the sender package info bail,
            // we need to identify the app to the user securely.
            finish(null);
            return;
        }

        chooseCertificate();
    }

    private void chooseCertificate() {
        // Start loading the set of certs to choose from now- if device policy doesn't return an
        // alias, having aliases loading already will save some time waiting for UI to start.
        KeyInfoProvider keyInfoProvider = new KeyInfoProvider() {
            public boolean isUserSelectable(String alias) {
                try (KeyChain.KeyChainConnection connection =
                        KeyChain.bind(KeyChainActivity.this)) {
                    return connection.getService().isUserSelectable(alias);
                }
                catch (InterruptedException ignored) {
                    Log.e(TAG, "interrupted while checking if key is user-selectable", ignored);
                    Thread.currentThread().interrupt();
                    return false;
                } catch (Exception ignored) {
                    Log.e(TAG, "error while checking if key is user-selectable", ignored);
                    return false;
                }
            }
        };

        String[] keyTypes = getIntent().getStringArrayExtra(KeyChain.EXTRA_KEY_TYPES);
        if (keyTypes == null) {
            keyTypes = new String[]{};
        }
        ArrayList<byte[]> issuers = (ArrayList<byte[]>) getIntent().getSerializableExtra(
                KeyChain.EXTRA_ISSUERS);
        if (issuers == null) {
            issuers = new ArrayList<byte[]>();
        }

        final AliasLoader loader = new AliasLoader(mKeyStore, this, keyInfoProvider,
                new CertificateParametersFilter(mKeyStore, keyTypes, issuers));
        loader.execute();

        final IKeyChainAliasCallback.Stub callback = new IKeyChainAliasCallback.Stub() {
            @Override public void alias(String alias) {
                // Use policy-suggested alias if provided
                if (alias != null) {
                    finishWithAliasFromPolicy(alias);
                    return;
                }

                // No suggested alias - instead finish loading and show UI to pick one
                final CertificateAdapter certAdapter;
                try {
                    certAdapter = loader.get();
                } catch (InterruptedException | ExecutionException e) {
                    Log.e(TAG, "Loading certificate aliases interrupted", e);
                    finish(null);
                    return;
                }
                /*
                 * If there are no keys for the user to choose from, do not display
                 * the dialog. This is in line with what other operating systems do.
                 */
                if (!certAdapter.hasKeysToChoose()) {
                    finish(null);
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        displayCertChooserDialog(certAdapter);
                    }
                });
            }
        };

        // Give a profile or device owner the chance to intercept the request, if a private key
        // access listener is registered with the DevicePolicyManagerService.
        IDevicePolicyManager devicePolicyManager = IDevicePolicyManager.Stub.asInterface(
                ServiceManager.getService(Context.DEVICE_POLICY_SERVICE));

        Uri uri = getIntent().getParcelableExtra(KeyChain.EXTRA_URI);
        String alias = getIntent().getStringExtra(KeyChain.EXTRA_ALIAS);
        try {
            devicePolicyManager.choosePrivateKeyAlias(mSenderUid, uri, alias, callback);
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to request alias from DevicePolicyManager", e);
            // Proceed without a suggested alias.
            try {
                callback.alias(null);
            } catch (RemoteException shouldNeverHappen) {
                finish(null);
            }
        }
    }

    @VisibleForTesting
    static class CertificateParametersFilter {
        private final KeyStore mKeyStore;
        private final List<String> mKeyTypes;
        private final List<X500Principal> mIssuers;

        public CertificateParametersFilter(KeyStore keyStore,
                @NonNull String[] keyTypes, @NonNull ArrayList<byte[]> issuers) {
            mKeyStore = keyStore;
            mKeyTypes = Arrays.asList(keyTypes);
            mIssuers = new ArrayList<X500Principal>();
            for (byte[] issuer : issuers) {
                try {
                    X500Principal issuerPrincipal = new X500Principal(issuer);
                    Log.i(TAG, "Added issuer: " + issuerPrincipal.getName());
                    mIssuers.add(new X500Principal(issuer));
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Skipping invalid issuer", e);
                }
            }
        }

        public boolean shouldPresentCertificate(String alias) {
            X509Certificate cert = loadCertificate(mKeyStore, alias);
            // If there's no certificate associated with the alias, skip.
            if (cert == null) {
                return false;
            }
            Log.i(TAG, String.format("Inspecting certificate %s aliased with %s",
                        cert.getSubjectDN().getName(), alias));

            // If the caller has provided a list of key types to restrict the certificates
            // offered for selection, skip this alias if the key algorithm is not in that
            // list.
            String keyAlgorithm = cert.getPublicKey().getAlgorithm();
            Log.i(TAG, String.format("Certificate key algorithm: %s", keyAlgorithm));
            if (!mKeyTypes.isEmpty() && !mKeyTypes.contains(keyAlgorithm)) {
                return false;
            }

            // If the caller has provided a list of issuers to restrict the certificates
            // offered for selection, skip this alias if the issuer is not in that list.
            X500Principal issuer = cert.getIssuerX500Principal();
            Log.i(TAG, String.format("Certificate issuer: %s", issuer.getName()));
            if (!mIssuers.isEmpty() && !mIssuers.contains(issuer)) {
                return false;
            }

            return true;
        }

        public boolean areIssuersOrKeyTypesSpecified() {
            return !(mIssuers.isEmpty() && mKeyTypes.isEmpty());
        }
    }

    @VisibleForTesting
    static class AliasLoader extends AsyncTask<Void, Void, CertificateAdapter> {
        private final KeyStore mKeyStore;
        private final Context mContext;
        private final KeyInfoProvider mInfoProvider;
        private final CertificateParametersFilter mCertificateFilter;

        public AliasLoader(KeyStore keyStore, Context context, KeyInfoProvider infoProvider,
                CertificateParametersFilter certificateFilter) {
          mKeyStore = keyStore;
          mContext = context;
          mInfoProvider = infoProvider;
          mCertificateFilter = certificateFilter;
        }

        @Override protected CertificateAdapter doInBackground(Void... params) {
            String[] aliasArray = mKeyStore.list(Credentials.USER_PRIVATE_KEY);
            List<String> rawAliasList = ((aliasArray == null)
                                      ? Collections.<String>emptyList()
                                      : Arrays.asList(aliasArray));

            return new CertificateAdapter(mKeyStore, mContext,
                    rawAliasList.stream().filter(mInfoProvider::isUserSelectable)
                    .filter(mCertificateFilter::shouldPresentCertificate)
                    .sorted().collect(Collectors.toList()),
                    mCertificateFilter.areIssuersOrKeyTypesSpecified());
        }
    }

    private void displayCertChooserDialog(final CertificateAdapter adapter) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        boolean empty = adapter.mAliases.isEmpty();
        int negativeLabel = empty ? android.R.string.cancel : R.string.deny_button;
        builder.setNegativeButton(negativeLabel, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int id) {
                dialog.cancel(); // will cause OnDismissListener to be called
            }
        });

        String title;
        int selectedItem = -1;
        Resources res = getResources();
        if (empty) {
            title = res.getString(R.string.title_no_certs);
        } else {
            title = res.getString(R.string.title_select_cert);
            String alias = getIntent().getStringExtra(KeyChain.EXTRA_ALIAS);

            if (alias != null) {
                // if alias was requested, set it if found
                int adapterPosition = adapter.mAliases.indexOf(alias);
                if (adapterPosition != -1) {
                    // increase by 1 to account for item 0 being the header.
                    selectedItem = adapterPosition + 1;
                }
            } else if (adapter.mAliases.size() == 1) {
                // if only one choice, preselect it
                selectedItem = 1;
            }

            builder.setPositiveButton(R.string.allow_button, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int id) {
                    if (dialog instanceof AlertDialog) {
                        ListView lv = ((AlertDialog) dialog).getListView();
                        int listViewPosition = lv.getCheckedItemPosition();
                        int adapterPosition = listViewPosition-1;
                        String alias = ((adapterPosition >= 0)
                                        ? adapter.getItem(adapterPosition)
                                        : null);
                        finish(alias);
                    } else {
                        Log.wtf(TAG, "Expected AlertDialog, got " + dialog, new Exception());
                        finish(null);
                    }
                }
            });
        }
        builder.setTitle(title);
        builder.setSingleChoiceItems(adapter, selectedItem, null);
        final AlertDialog dialog = builder.create();

        // Show text above the list to explain what the certificate will be used for.
        TextView contextView = (TextView) View.inflate(this, R.layout.cert_chooser_header, null);

        final ListView lv = dialog.getListView();
        lv.addHeaderView(contextView, null, false);
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position == 0) {
                    // Header. Just text; ignore clicks.
                    return;
                } else {
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(true);
                    lv.setItemChecked(position, true);
                    adapter.notifyDataSetChanged();
                }
            }
        });

        // getTargetPackage guarantees that the returned string is
        // supplied by the system, so that an application can not
        // spoof its package.
        String pkg = mSender.getIntentSender().getTargetPackage();
        PackageManager pm = getPackageManager();
        CharSequence applicationLabel;
        try {
            applicationLabel = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            applicationLabel = pkg;
        }
        String appMessage = String.format(res.getString(R.string.requesting_application),
                                          applicationLabel);
        String contextMessage = appMessage;
        Uri uri = getIntent().getParcelableExtra(KeyChain.EXTRA_URI);
        if (uri != null) {
            String hostMessage = String.format(res.getString(R.string.requesting_server),
                                               uri.getAuthority());
            if (contextMessage == null) {
                contextMessage = hostMessage;
            } else {
                contextMessage += " " + hostMessage;
            }
        }
        contextView.setText(contextMessage);

        if (selectedItem == -1) {
            dialog.setOnShowListener(new DialogInterface.OnShowListener() {
                @Override
                public void onShow(DialogInterface dialogInterface) {
                     dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(false);
                }
            });
        }
        dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override public void onCancel(DialogInterface dialog) {
                finish(null);
            }
        });
        dialog.create();
        // Prevents screen overlay attack.
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setFilterTouchesWhenObscured(true);
        dialog.show();
    }

    @VisibleForTesting
    static class CertificateAdapter extends BaseAdapter {
        private final List<String> mAliases;
        private final List<String> mSubjects = new ArrayList<String>();
        private final KeyStore mKeyStore;
        private final Context mContext;
        private final boolean mIssuersOrKeyTypesSpecified;

        private CertificateAdapter(KeyStore keyStore, Context context, List<String> aliases,
                boolean issuersOrKeyTypesSpecified) {
            mAliases = aliases;
            mSubjects.addAll(Collections.nCopies(aliases.size(), (String) null));
            mKeyStore = keyStore;
            mContext = context;
            mIssuersOrKeyTypesSpecified = issuersOrKeyTypesSpecified;
        }
        @Override public int getCount() {
            return mAliases.size();
        }
        @Override public String getItem(int adapterPosition) {
            return mAliases.get(adapterPosition);
        }
        @Override public long getItemId(int adapterPosition) {
            return adapterPosition;
        }
        @Override public View getView(final int adapterPosition, View view, ViewGroup parent) {
            ViewHolder holder;
            if (view == null) {
                LayoutInflater inflater = LayoutInflater.from(mContext);
                view = inflater.inflate(R.layout.cert_item, parent, false);
                holder = new ViewHolder();
                holder.mAliasTextView = (TextView) view.findViewById(R.id.cert_item_alias);
                holder.mSubjectTextView = (TextView) view.findViewById(R.id.cert_item_subject);
                holder.mRadioButton = (RadioButton) view.findViewById(R.id.cert_item_selected);
                view.setTag(holder);
            } else {
                holder = (ViewHolder) view.getTag();
            }

            String alias = mAliases.get(adapterPosition);

            holder.mAliasTextView.setText(alias);

            String subject = mSubjects.get(adapterPosition);
            if (subject == null) {
                new CertLoader(adapterPosition, holder.mSubjectTextView).execute();
            } else {
                holder.mSubjectTextView.setText(subject);
            }

            ListView lv = (ListView)parent;
            int listViewCheckedItemPosition = lv.getCheckedItemPosition();
            int adapterCheckedItemPosition = listViewCheckedItemPosition-1;
            holder.mRadioButton.setChecked(adapterPosition == adapterCheckedItemPosition);
            return view;
        }

        /**
         * Returns true if there are keys to choose from.
         */
        public boolean hasKeysToChoose() {
            return !mAliases.isEmpty();
        }

        private class CertLoader extends AsyncTask<Void, Void, String> {
            private final int mAdapterPosition;
            private final TextView mSubjectView;
            private CertLoader(int adapterPosition, TextView subjectView) {
                mAdapterPosition = adapterPosition;
                mSubjectView = subjectView;
            }
            @Override protected String doInBackground(Void... params) {
                String alias = mAliases.get(mAdapterPosition);
                X509Certificate cert = loadCertificate(mKeyStore, alias);
                if (cert == null) {
                    return null;
                }
                // bouncycastle can handle the emailAddress OID of 1.2.840.113549.1.9.1
                X500Principal subjectPrincipal = cert.getSubjectX500Principal();
                X509Name subjectName = X509Name.getInstance(subjectPrincipal.getEncoded());
                return subjectName.toString(true, X509Name.DefaultSymbols);
            }
            @Override protected void onPostExecute(String subjectString) {
                mSubjects.set(mAdapterPosition, subjectString);
                mSubjectView.setText(subjectString);
            }
        }
    }

    private static class ViewHolder {
        TextView mAliasTextView;
        TextView mSubjectTextView;
        RadioButton mRadioButton;
    }

    private void finish(String alias) {
        finish(alias, false);
    }

    private void finishWithAliasFromPolicy(String alias) {
        finish(alias, true);
    }

    private void finish(String alias, boolean isAliasFromPolicy) {
        if (alias == null) {
            setResult(RESULT_CANCELED);
        } else {
            Intent result = new Intent();
            result.putExtra(Intent.EXTRA_TEXT, alias);
            setResult(RESULT_OK, result);
        }
        IKeyChainAliasCallback keyChainAliasResponse
                = IKeyChainAliasCallback.Stub.asInterface(
                        getIntent().getIBinderExtra(KeyChain.EXTRA_RESPONSE));
        if (keyChainAliasResponse != null) {
            new ResponseSender(keyChainAliasResponse, alias, isAliasFromPolicy).execute();
            return;
        }
        finish();
    }

    private class ResponseSender extends AsyncTask<Void, Void, Void> {
        private IKeyChainAliasCallback mKeyChainAliasResponse;
        private String mAlias;
        private boolean mFromPolicy;

        private ResponseSender(IKeyChainAliasCallback keyChainAliasResponse, String alias,
                boolean isFromPolicy) {
            mKeyChainAliasResponse = keyChainAliasResponse;
            mAlias = alias;
            mFromPolicy = isFromPolicy;
        }
        @Override protected Void doInBackground(Void... unused) {
            try {
                if (mAlias != null) {
                    KeyChain.KeyChainConnection connection = KeyChain.bind(KeyChainActivity.this);
                    try {
                        // This is a safety check to make sure an alias was not somehow chosen by
                        // the user but is not user-selectable.
                        // However, if the alias was selected by the Device Owner / Profile Owner
                        // (by implementing DeviceAdminReceiver), then there's no need to check
                        // this.
                        if (!mFromPolicy && (!connection.getService().isUserSelectable(mAlias))) {
                            Log.w(TAG, String.format("Alias %s not user-selectable.", mAlias));
                            //TODO: Should we invoke the callback with null here to indicate error?
                            return null;
                        }
                        connection.getService().setGrant(mSenderUid, mAlias, true);
                    } finally {
                        connection.close();
                    }
                }
                mKeyChainAliasResponse.alias(mAlias);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                Log.d(TAG, "interrupted while granting access", ignored);
            } catch (Exception ignored) {
                // don't just catch RemoteException, caller could
                // throw back a RuntimeException across processes
                // which we should protect against.
                Log.e(TAG, "error while granting access", ignored);
            }
            return null;
        }
        @Override protected void onPostExecute(Void unused) {
            finish();
        }
    }

    @Override public void onBackPressed() {
        finish(null);
    }

    private static X509Certificate loadCertificate(KeyStore keyStore, String alias) {
        byte[] bytes = keyStore.get(Credentials.USER_CERTIFICATE + alias);
        if (bytes == null) {
            return null;
        }
        InputStream in = new ByteArrayInputStream(bytes);
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate)cf.generateCertificate(in);
        } catch (CertificateException ignored) {
            Log.w(TAG, "Error generating certificate", ignored);
            return null;
        }
    }
}
