/*
 * Copyright (C) 2013 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.opendatakit.sensors.manager;

import java.util.Iterator;
import java.util.List;

import org.json.JSONObject;
import org.opendatakit.aggregate.odktables.rest.ElementDataType;
import org.opendatakit.aggregate.odktables.rest.ElementType;
import org.opendatakit.common.android.data.ColumnDefinition;
import org.opendatakit.common.android.data.OrderedColumns;
import org.opendatakit.common.android.provider.DataTableColumns;
import org.opendatakit.common.android.utilities.ODKDataUtils;
import org.opendatakit.common.android.utilities.ODKJsonNames;
import org.opendatakit.database.DatabaseConsts;
import org.opendatakit.database.service.OdkDbHandle;
import org.opendatakit.database.service.OdkDbInterface;
import org.opendatakit.sensors.DataSeries;
import org.opendatakit.sensors.DriverType;
import org.opendatakit.sensors.ODKSensor;

import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.SQLException;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

/**
 * 
 * @author wbrunette@gmail.com
 * @author rohitchaudhri@gmail.com
 * 
 */
public class WorkerThread extends Thread {
  private static final String TAG = "WorkerThread";

  private boolean isRunning;
  private Context serviceContext;
  private ODKSensorManager sensorManager;
  private ServiceConnectionWrapper databaseServiceConnection = null;
  private OdkDbInterface databaseService = null;
  
  /**
   * Wrapper class for service activation management.
   * 
   * @author mitchellsundt@gmail.com
   *
   */
  private final class ServiceConnectionWrapper implements ServiceConnection {

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
      WorkerThread.this.doServiceConnected(name, service);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
      WorkerThread.this.doServiceDisconnected(name);
    }
  }
  
  private void unbindDatabaseBinderWrapper() {
    try {
      ServiceConnectionWrapper tmp = databaseServiceConnection;
      databaseServiceConnection = null;
      if ( tmp != null ) {
        serviceContext.unbindService(tmp);
      }
    } catch ( Exception e ) {
      // ignore
      e.printStackTrace();
    }
  }
  
  private void shutdownServices() {
    Log.i(TAG, "shutdownServices - Releasing WebServer and DbShim service");
    databaseService = null;
    unbindDatabaseBinderWrapper();
  }
  
  private void bindToService() {
    if (isRunning) {
      if (databaseService == null && 
          databaseServiceConnection == null) {
        Log.i(TAG, "Attempting bind to Database service");
        databaseServiceConnection = new ServiceConnectionWrapper();
        Intent bind_intent = new Intent();
        bind_intent.setClassName(DatabaseConsts.DATABASE_SERVICE_PACKAGE,
            DatabaseConsts.DATABASE_SERVICE_CLASS);
        serviceContext.bindService(
            bind_intent,
            databaseServiceConnection,
            Context.BIND_AUTO_CREATE
                | ((Build.VERSION.SDK_INT >= 14) ? Context.BIND_ADJUST_WITH_ACTIVITY : 0));
      }
    }
  }
  
  private void doServiceConnected(ComponentName className, IBinder service) {

    if (className.getClassName().equals(DatabaseConsts.DATABASE_SERVICE_CLASS)) {
      Log.i(TAG, "Bound to Database service");
      databaseService = OdkDbInterface.Stub.asInterface(service);
    }
  }

  public OdkDbInterface getDatabase() {
    return databaseService;
  }

  private void doServiceDisconnected(ComponentName className) {

    if (className.getClassName().equals(DatabaseConsts.DATABASE_SERVICE_CLASS)) {
      if (!isRunning) {
        Log.i(TAG, "Unbound from Database service (intentionally)");
      } else {
        Log.w(TAG, "Unbound from Database service (unexpected)");
      }
      databaseService = null;
      unbindDatabaseBinderWrapper();
    }

    // the bindToService() method decides whether to connect or not...
    bindToService();
  }

  public WorkerThread(Context context, ODKSensorManager manager) {
    super("WorkerThread");
    isRunning = true;
    serviceContext = context;
    sensorManager = manager;
  }

  public void stopthread() {
    isRunning = false;
    this.interrupt();
  }

  @Override
  public void run() {
    Log.d(TAG, "worker thread started");

    while (isRunning) {
      bindToService();
  
      while (isRunning && (getDatabase() != null)) {
        try {
          for (ODKSensor sensor : sensorManager.getSensorsUsingAppForDatabase()) {
            moveSensorDataToCP(sensor);
          }
  
          Thread.sleep(3000);
        } catch (InterruptedException iex) {
          iex.printStackTrace();
        }
      }
    }
    
    shutdownServices();
  }

  private void moveSensorDataToCP(ODKSensor aSensor) {
    if (aSensor != null) {
      List<Bundle> bundles = aSensor.getSensorData(0);// XXX for now this gets
                                                      // all data fm sensor
      if (bundles == null) {
        Log.e(TAG, "WTF null list of bundles~");
      } else {
        Iterator<Bundle> iter = bundles.iterator();
        while (iter.hasNext()) {
          Bundle aBundle = iter.next();

          DriverType driver = sensorManager.getSensorDriverType(aSensor.getSensorID());
          if (driver != null && driver.getTableDefinitionStr() != null) {
            parseSensorDataAndInsertIntoTable(aSensor, driver.getTableDefinitionStr(), aBundle);
          }

        }
      }
    }
  }

  private void parseSensorDataAndInsertIntoTable(ODKSensor aSensor, String strTableDef,
      Bundle dataBundle) {
    JSONObject jsonTableDef = null;
    ContentValues tablesValues = new ContentValues();

    boolean insertSuccess = false;
    OdkDbHandle db = null;
    try {
      db = getDatabase().openDatabase(aSensor.getAppNameForDatabase(), true);

      jsonTableDef = new JSONObject(strTableDef);

      String tableId = jsonTableDef.getJSONObject(ODKJsonNames.jsonTableStr).getString(
          ODKJsonNames.jsonTableIdStr);

      if (tableId == null) {
        return;
      }

      boolean success;

      success = false;
      try {
        success = getDatabase().hasTableId(aSensor.getAppNameForDatabase(), db, tableId);
      } catch (Exception e) {
        e.printStackTrace();
        throw new SQLException("Exception testing for tableId " + tableId);
      }
      if (!success) {
        sensorManager.parseDriverTableDefintionAndCreateTable(this, 
            aSensor.getAppNameForDatabase(), db, aSensor.getSensorID());
      }

      success = false;
      try {
        success = getDatabase().hasTableId(aSensor.getAppNameForDatabase(), db, tableId);
      } catch (Exception e) {
        e.printStackTrace();
        throw new SQLException("Exception testing for tableId " + tableId);
      }
      if (!success) {
        throw new SQLException("Unable to create tableId " + tableId);
      }

      OrderedColumns orderedDefs = getDatabase().getUserDefinedColumns(aSensor.getAppNameForDatabase(), db, tableId);

      // Create the columns for the driver table
      for (ColumnDefinition col : orderedDefs.getColumnDefinitions()) {
        if (!col.isUnitOfRetention()) {
          continue;
        }

        String colName = col.getElementKey();
        ElementType type = col.getType();

        if (colName.equals(DataSeries.SENSOR_ID)) {

          // special treatment
          tablesValues.put(colName, aSensor.getSensorID());

        } else if (type.getDataType() == ElementDataType.bool) {

          Boolean boolColData = dataBundle.containsKey(colName) ? dataBundle.getBoolean(colName)
              : null;
          Integer colData = (boolColData == null) ? null : (boolColData ? 1 : 0);
          tablesValues.put(colName, colData);

        } else if (type.getDataType() == ElementDataType.integer) {

          Integer colData = dataBundle.containsKey(colName) ? dataBundle.getInt(colName) : null;
          tablesValues.put(colName, colData);

        } else if (type.getDataType() == ElementDataType.number) {

          Double colData = dataBundle.containsKey(colName) ? dataBundle.getDouble(colName) : null;
          tablesValues.put(colName, colData);

        } else {
          // everything else is a string value coming across the wire...
          String colData = dataBundle.containsKey(colName) ? dataBundle.getString(colName) : null;
          tablesValues.put(colName, colData);
        }
      }

      if (tablesValues.size() > 0) {
        Log.i(TAG, "Writing db values for sensor:" + aSensor.getSensorID());
        String rowId = tablesValues.containsKey(DataTableColumns.ID) ? tablesValues
            .getAsString(DataTableColumns.ID) : null;
        if (rowId == null) {
          rowId = ODKDataUtils.genUUID();
        }
        getDatabase().insertDataIntoExistingDBTableWithId(aSensor.getAppNameForDatabase(), db, tableId, orderedDefs,
            tablesValues, rowId);
      }
      insertSuccess = true;

    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      if (db != null) {
        try {
          getDatabase().closeTransactionAndDatabase(aSensor.getAppNameForDatabase(), db, insertSuccess);
        } catch (RemoteException e) {
          e.printStackTrace();
        }
      }
    }
  }
}