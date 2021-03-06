package org.ovirt.mobile.movirt.provider;

import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Loader;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;
import android.util.Log;

import org.androidannotations.annotations.AfterInject;
import org.androidannotations.annotations.EBean;
import org.androidannotations.annotations.RootContext;
import org.ovirt.mobile.movirt.model.BaseEntity;
import org.ovirt.mobile.movirt.model.EntityMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@EBean
public class ProviderFacade {
    public static final String TAG = ProviderFacade.class.getSimpleName();

    @RootContext
    Context context;

    private ContentProviderClient contentClient;

    @AfterInject
    void initContentProviderClient() {
        contentClient = context.getContentResolver().acquireContentProviderClient(OVirtContract.BASE_CONTENT_URI);
    }

    public class QueryBuilder<E> {
        private static final String URI_FIELD_NAME = "CONTENT_URI";

        private final Class<E> clazz;
        private final Uri baseUri;

        StringBuilder selection = new StringBuilder();
        List<String> selectionArgs = new ArrayList<>();
        StringBuilder sortOrder = new StringBuilder();

        public QueryBuilder(Class<E> clazz) {
            this.clazz = clazz;
            try {
                this.baseUri = (Uri) clazz.getField(URI_FIELD_NAME).get(null);
            } catch (IllegalAccessException | NoSuchFieldException e) {
                throw new RuntimeException("Assertion error: Class: " + clazz + " does not define static field " + URI_FIELD_NAME, e);
            }
        }

        public QueryBuilder<E> id(String value) {
            return where(OVirtContract.BaseEntity.ID, value);
        }

        public QueryBuilder<E> where(String columnName, String value) {
            assert !columnName.equals("") : "columnName cannot be empty or null";

            if (selection.length() > 0) {
                selection.append("AND ");
            }
            selection.append(columnName);
            if (value == null) {
                selection.append(" IS NULL ");
            } else {
                selection.append(" = ? ");
                selectionArgs.add(value);
            }

            return this;
        }

        public QueryBuilder<E> orderBy(String columnName) {
            return orderBy(columnName, SortOrder.ASCENDING);
        }

        public QueryBuilder<E> orderByAscending(String columnName) {
            return orderBy(columnName);
        }

        public QueryBuilder<E> orderByDescending(String columnName) {
            return orderBy(columnName, SortOrder.DESCENDING);
        }

        public QueryBuilder<E> orderBy(String columnName, SortOrder order) {
            assert !columnName.equals("") : "columnName cannot be empty or null";

            sortOrder.append(columnName);
            sortOrder.append(order == SortOrder.ASCENDING ? " ASC " : " DESC ");

            return this;
        }

        public Cursor asCursor() {
            try {
                return contentClient.query(baseUri,
                                           null,
                                           selection.toString(),
                                           selectionArgs.toArray(new String[selectionArgs.size()]),
                                           sortOrder.toString());
            } catch (RemoteException e) {
                Log.e(TAG, "Error querying " + baseUri, e);
                throw new RuntimeException(e);
            }
        }

        public Loader<Cursor> asLoader() {
            return new CursorLoader(context,
                                    baseUri,
                                    null,
                                    selection.toString(),
                                    selectionArgs.toArray(new String[selectionArgs.size()]),
                                    sortOrder.toString());
        }

        public Collection<E> all() {
            Cursor cursor = asCursor();
            List<E> result = new ArrayList<>();
            while (cursor.moveToNext()) {
                result.add(EntityMapper.forEntity(clazz).fromCursor(cursor));
            }
            return result;
        }
    }

    public class BatchBuilder {
        private ArrayList<ContentProviderOperation> batch = new ArrayList<>();

        public <E extends BaseEntity<?>> BatchBuilder insert(E entity) {
            batch.add(ContentProviderOperation.newInsert(entity.getBaseUri()).withValues(entity.toValues()).build());
            return this;
        }

        public <E extends BaseEntity<?>> BatchBuilder update(E entity) {
            batch.add(ContentProviderOperation.newUpdate(entity.getUri()).withValues(entity.toValues()).build());
            return this;
        }

        public <E extends BaseEntity<?>> BatchBuilder delete(E entity) {
            batch.add(ContentProviderOperation.newDelete(entity.getUri()).build());
            return this;
        }

        public void apply() {
            try {
                contentClient.applyBatch(batch);
            } catch (RemoteException | OperationApplicationException e) {
                throw new RuntimeException("Batch apply failed", e);
            }
        }

        public boolean isEmpty() {
            return batch.isEmpty();
        }
    }

    public <E extends BaseEntity<?>> QueryBuilder<E> query(Class<E> clazz) {
        return new QueryBuilder<>(clazz);
    }

    public <E extends BaseEntity<?>> void insert(E entity) {
        try {
            contentClient.insert(entity.getBaseUri(), entity.toValues());
        } catch (RemoteException e) {
            Log.e(TAG, "Error inserting entity: " + entity, e);
        }
    }

    public <E extends BaseEntity<?>> void update(E entity) {
        try {
            contentClient.update(entity.getUri(), entity.toValues(), null, null);
        } catch (RemoteException e) {
            Log.e(TAG, "Error updating entity: " + entity, e);
        }
    }

    public <E extends BaseEntity<?>> void delete(E entity) {
        try {
            contentClient.delete(entity.getUri(), null, null);
        } catch (RemoteException e) {
            Log.e(TAG, "Error deleting entity: " + entity, e);
        }
    }

    public BatchBuilder batch() {
        return new BatchBuilder();
    }

    public int getLastEventId() {
        try {
            Cursor cursor = contentClient.query(OVirtContract.Event.CONTENT_URI,
                                                new String[]{"MAX(" + OVirtContract.Event.ID + ")"},
                                                null,
                                                null,
                                                null);
            if (cursor.moveToNext()) {
                return cursor.getInt(0);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error determining last event id", e);
            throw new RuntimeException(e);
        }
        return 0;
    }
}
