package com.mangaglass.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

/** The schema accepts only timestamps and durations, with a hard limit of 30 attempts. */
final class TranslationTimingStore extends SQLiteOpenHelper {
    static final int LIMIT=30;
    private static TranslationTimingStore instance;
    static synchronized TranslationTimingStore get(Context context) {
        if(instance==null) instance=new TranslationTimingStore(context.getApplicationContext(),"translation_timing.db");
        return instance;
    }
    TranslationTimingStore(Context context,String name) { super(context,name,null,2); }
    @Override public void onCreate(SQLiteDatabase db) {
        StringBuilder sql=new StringBuilder("CREATE TABLE timings (started_ms INTEGER NOT NULL,total_ms INTEGER NOT NULL,first_frame_ms INTEGER NOT NULL");
        for(TranslationTiming.Stage stage:TranslationTiming.Stage.values()) sql.append(',').append(stage.column).append(" INTEGER NOT NULL");
        for(TranslationTiming.NetworkPhase phase:TranslationTiming.NetworkPhase.values()) sql.append(',').append(phase.column).append(" INTEGER NOT NULL DEFAULT -1");
        db.execSQL(sql.append(')').toString());
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        if(oldVersion<2) for(TranslationTiming.NetworkPhase phase:TranslationTiming.NetworkPhase.values()) {
            db.execSQL("ALTER TABLE timings ADD COLUMN "+phase.column+" INTEGER NOT NULL DEFAULT -1");
        }
    }
    synchronized void record(TranslationTiming.Record record) {
        ContentValues values=new ContentValues();
        values.put("started_ms",record.startedAtMillis); values.put("total_ms",record.totalMillis);
        values.put("first_frame_ms",record.firstFrameMillis);
        for(TranslationTiming.Stage stage:TranslationTiming.Stage.values()) values.put(stage.column,record.duration(stage));
        for(TranslationTiming.NetworkPhase phase:TranslationTiming.NetworkPhase.values()) values.put(phase.column,record.duration(phase));
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            db.insertOrThrow("timings",null,values);
            db.execSQL("DELETE FROM timings WHERE rowid NOT IN (SELECT rowid FROM timings ORDER BY rowid DESC LIMIT 30)");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    synchronized List<TranslationTiming.Record> recent() {
        List<TranslationTiming.Record> records=new ArrayList<>();
        try(Cursor cursor=getReadableDatabase().query("timings",null,null,null,null,null,"rowid DESC","30")) {
            while(cursor.moveToNext()) {
                long[] durations=new long[TranslationTiming.Stage.values().length];
                for(TranslationTiming.Stage stage:TranslationTiming.Stage.values()) durations[stage.ordinal()]=cursor.getLong(cursor.getColumnIndexOrThrow(stage.column));
                long[] network=new long[TranslationTiming.NetworkPhase.values().length];
                for(TranslationTiming.NetworkPhase phase:TranslationTiming.NetworkPhase.values()) network[phase.ordinal()]=cursor.getLong(cursor.getColumnIndexOrThrow(phase.column));
                records.add(new TranslationTiming.Record(cursor.getLong(cursor.getColumnIndexOrThrow("started_ms")),
                        cursor.getLong(cursor.getColumnIndexOrThrow("total_ms")),cursor.getLong(cursor.getColumnIndexOrThrow("first_frame_ms")),durations,network));
            }
        }
        return records;
    }
    synchronized void clear() { getWritableDatabase().delete("timings",null,null); }
}
