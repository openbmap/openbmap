/*
	Radiobeacon - Openbmap wifi and cell logger
    Copyright (C) 2013  wish7

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation, either version 3 of the
    License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.openbmap.activities;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import org.openbmap.R;
import org.openbmap.RadioBeacon;
import org.openbmap.db.DataHelper;
import org.openbmap.db.RadioBeaconContentProvider;
import org.openbmap.db.Schema;
import org.openbmap.db.models.Session;
import org.openbmap.utils.ActionModeUtils;
import org.openbmap.utils.ActionModeUtils.LongClickCallback;
import org.openbmap.utils.OnAlertClickInterface;

import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager.LoaderCallbacks;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.widget.SimpleCursorAdapter;
import android.support.v4.widget.SimpleCursorAdapter.ViewBinder;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.actionbarsherlock.app.SherlockDialogFragment;
import com.actionbarsherlock.app.SherlockFragmentActivity;
import com.actionbarsherlock.app.SherlockListFragment;

/**
 * Fragment for displaying all sessions
 */
public class SessionListFragment extends SherlockListFragment implements
LoaderCallbacks<Cursor>, LongClickCallback, OnAlertClickInterface {
	
	private static final String	TAG	= SessionListFragment.class.getSimpleName();

	/**
	 * Dialog id
	 */
	private static final int ID_MULTIPLE_UPLOADS	= 1;

	private SimpleCursorAdapter	mAdapter;

	private boolean mAdapterUpdatePending = true;

	private ContentObserver	mObserver;

	/**
	 * List action bar commands
	 */
	public interface SessionFragementListener {
		void deleteCommand(int id);
		/**
		 * Creates a new session.
		 */
		void startCommand();
		/**
		 * Stops session
		 */
		void stopCommand(int id);
		/**
		 * Resumes session.
		 * @param id
		 *		Session to resume
		 */
		void resumeCommand(int id);
		/**
		 * Deletes all sessions.
		 * @param id
		 *		Session to resume
		 */
		void deleteAllCommand();
		/**
		 * Exports and uploads session.
		 * @param id
		 *		Session to resume
		 */
		void exportCommand(int id);
		/**
		 * Exports and uploads all sessions not yet uploaded
		 */
		void exportAllCommand();

		void reloadListFragment();
	}

	/**
	 * @see http://stackoverflow.com/questions/6317767/cant-add-a-headerview-to-a-listfragment
	 *      Fragment lifecycle
	 *      onAttach(Activity) called once the fragment is associated with its activity.
	 *      onCreate(Bundle) called to do initial creation of the fragment.
	 *      onCreateView(LayoutInflater, ViewGroup, Bundle) creates and returns the view hierarchy associated with the fragment.
	 *      onActivityCreated(Bundle) tells the fragment that its activity has completed its own Activity.onCreate.
	 *      onStart() makes the fragment visible to the user (based on its containing activity being started).
	 *      onResume() makes the fragment interacting with the user (based on its containing activity being resumed).
	 */

	@Override
	public final void onActivityCreated(final Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);

		String[] from = new String[] {
				Schema.COL_ID,
				Schema.COL_CREATED_AT,
				Schema.COL_IS_ACTIVE,
				Schema.COL_HAS_BEEN_EXPORTED,
				Schema.COL_NUMBER_OF_CELLS,
				Schema.COL_NUMBER_OF_WIFIS
		};

		int[] to = new int[] {
				R.id.sessionlistfragment_id,
				R.id.sessionlistfragment_created_at,
				R.id.sessionlistfragment_statusicon,
				R.id.sessionlistfragment_uploadicon,
				R.id.sessionlistfragment_no_cells,
				R.id.sessionlistfragment_no_wifis};

		mAdapter = new SimpleCursorAdapter(getActivity().getBaseContext(),
				R.layout.sessionlistfragment, null, from, to, 0);
		mAdapter.setViewBinder(new SessionViewBinder());

		// Trying to add a Header View.
		View header = (View) getLayoutInflater(savedInstanceState).inflate(
				R.layout.sessionlistheader, null);
		this.getListView().addHeaderView(header);

		// setup data adapters
		setListAdapter(mAdapter);
		getActivity().getSupportLoaderManager().initLoader(0, null, this);

		// register for change notifications
		mObserver = new ContentObserver(new Handler()) {
			@Override
			public void onChange(final boolean selfChange) {
				refreshAdapter();
			};
		};

		getListView().setLongClickable(true);
		getListView().setChoiceMode(ListView.CHOICE_MODE_SINGLE);
		getListView().setOnItemLongClickListener(new ActionModeUtils(
				(SherlockFragmentActivity) this.getSherlockActivity(), R.menu.session_context, this,
				getListView()));
	}

	@Override
	public final void onResume() {
		super.onResume();
		getActivity().getContentResolver().registerContentObserver(RadioBeaconContentProvider.CONTENT_URI_SESSION, true, mObserver);
	}

	@Override
	public final void onPause() {
		getActivity().getContentResolver().unregisterContentObserver(mObserver);

		super.onPause();
	}

	@Override
	public final void onDestroy() {
		setListAdapter(null);

		super.onDestroy();
	}

	/**
	 * Stops session (and services)
	 */
	private void stop(final int id) {
		((SessionFragementListener) getSherlockActivity()).stopCommand(id);
	}

	/**
	 * Resumes session, if session hasn't been uploaded yet.
	 */
	private void resume(final int id) {
		DataHelper datahelper = new DataHelper(this.getActivity());
		Session check = datahelper.loadSession(id);
		if (check != null && !check.hasBeenExported()) {
			((SessionFragementListener) getSherlockActivity()).resumeCommand(id);
		} else {
			Toast.makeText(this.getActivity(), R.string.warning_session_closed, Toast.LENGTH_SHORT).show();
		}
	}

	/*
	 * OnListClick resumes corresponding session.
	 */
	@Override
	public final void onListItemClick(final ListView lv, final View iv, final int position, final long id) {
		// ignore clicks on list header (position 0)
		if (position != 0) {
			resume((int) id);
		}
	}

	@Override
	public final Loader<Cursor> onCreateLoader(final int arg0, final Bundle arg1) {
		String[] projection = {
				Schema.COL_ID,
				Schema.COL_CREATED_AT,
				Schema.COL_IS_ACTIVE, 
				Schema.COL_HAS_BEEN_EXPORTED, 
				Schema.COL_NUMBER_OF_CELLS,
				Schema.COL_NUMBER_OF_WIFIS
		};
		CursorLoader cursorLoader = new CursorLoader(getActivity().getBaseContext(),
				RadioBeaconContentProvider.CONTENT_URI_SESSION, projection, null, null, Schema.COL_CREATED_AT + " DESC");
		return cursorLoader;
	}

	@Override
	public final void onLoadFinished(final Loader<Cursor> arg0, final Cursor cursor) {
		mAdapter.swapCursor(cursor);
		mAdapterUpdatePending = false;
	}

	@Override
	public final void onLoaderReset(final Loader<Cursor> arg0) {
		Log.d(TAG, "onLoadReset called");
		mAdapter.swapCursor(null);
	}

	/**
	 * Forces an adapter refresh.
	 */
	public final void refreshAdapter() {
		if (!mAdapterUpdatePending) {
			mAdapterUpdatePending = true;
			getActivity().getSupportLoaderManager().restartLoader(0, null, this);
		} else {
			Log.d(TAG, "refreshAdapter skipped. Another update is in progress");
		}
	}

	/**
	 * Replaces column values with icons and formats date to human-readable format.
	 */
	private static class SessionViewBinder implements ViewBinder {
		public boolean setViewValue(final View view, final Cursor cursor, final int columnIndex) {
			ImageView imgStatus = (ImageView) view.findViewById(R.id.sessionlistfragment_statusicon);
			ImageView imgUpload = (ImageView) view.findViewById(R.id.sessionlistfragment_uploadicon);
			TextView tvCreatedAt = (TextView) view.findViewById(R.id.sessionlistfragment_created_at);
			if (columnIndex == cursor.getColumnIndex(Schema.COL_IS_ACTIVE)) {
				//Log.d(TAG, "Modifying col " + cursor.getColumnIndex(Schema.COL_IS_ACTIVE));
				// symbol for active track
				int result = cursor.getInt(columnIndex);
				if (result > 0) {
					// Yellow clock icon for Active
					imgStatus.setImageResource(android.R.drawable.presence_away);
					imgStatus.setVisibility(View.VISIBLE);
				} else {
					imgStatus.setVisibility(View.INVISIBLE);				
				}
				return true;
			} else if (columnIndex == cursor.getColumnIndex(Schema.COL_HAS_BEEN_EXPORTED)) {
				// symbol for uploaded tracks
				int result = cursor.getInt(columnIndex);
				if (result > 0) {
					// Lock icon for uploaded sessions
					imgUpload.setImageResource(android.R.drawable.ic_lock_lock);
					imgUpload.setVisibility(View.VISIBLE);
				} else {
					imgUpload.setVisibility(View.INVISIBLE);				
				}
				return true;
			} else if (columnIndex == cursor.getColumnIndex(Schema.COL_CREATED_AT)) {
				Date result = new Date(cursor.getLong(columnIndex));
				SimpleDateFormat date = new SimpleDateFormat("yyyyMMdd", Locale.US);
				tvCreatedAt.setText(date.format(result));
				return true;
			}
			return false;
		}
	}

	/* (non-Javadoc)
	 * @see org.openbmap.utils.ActionModeHelper.ItemLongClickCallback#performAction(int, int)
	 */
	@Override
	public boolean onItemLongClick(int item, int position, int id) {
		// cancel if underlying item has gone..
		if (id == -1) {
			Log.i(TAG, "Skipping item click - nothing selected");
			return true;
		}

		switch (item) {
			case R.id.menu_upload_session:
				DataHelper datahelper = new DataHelper(this.getActivity());
				int pending = datahelper.countPendingExports();
				if (pending > 1) {
					AlertDialogHelper.newInstance(this, ID_MULTIPLE_UPLOADS, R.string.dialog_found_pending_uploads_title, R.string.dialog_found_pending_uploads_message, String.valueOf(id), false).show(getSherlockActivity().getSupportFragmentManager(), "multiple");
				} else {
					stop(id);
					((SessionFragementListener) getSherlockActivity()).exportCommand(id);
				}
				return true;
			case R.id.menu_delete_session:
				((SessionFragementListener) getSherlockActivity()).deleteCommand(id);
				return true;
			case R.id.menu_delete_all_sessions:
				((SessionFragementListener) getSherlockActivity()).deleteAllCommand();
				return true;
			case R.id.menu_resume_session:
				resume(id);
				return true;
			case R.id.menu_stop_session:
				stop(id);
				return true;
			default:
				break;
		}
		return true;
	}
	
	public static class AlertDialogHelper extends SherlockDialogFragment{

		/**
		 * Creates a new alert dialog
		 * @param id Alert dialog id
		 * @param title Alert title resource
		 * @param message Alert message resource
		 * @param args opional argument (e.g. session id)
		 * @param neutralOnly show only neutral button
		 * @return
		 */
		public static AlertDialogHelper newInstance(Fragment container, int id, int title, int message, String args, boolean neutralOnly) {
			AlertDialogHelper frag = new AlertDialogHelper();
			frag.setTargetFragment(container, id);
			// Caution: Don't set setRetainInstance(true) explicitly. This will cause the dialog to disappear
			// see http://stackoverflow.com/questions/11307390/dialogfragment-disappears-on-rotation-despite-setretaininstancetrue
			//frag.setRetainInstance(true);
			Bundle bundle = new Bundle();
			bundle.putInt("dialog_id", id);
			bundle.putInt("title", title);
			bundle.putInt("message", message);
			bundle.putString("args", args);
			bundle.putBoolean("only_neutral", neutralOnly);
			frag.setArguments(bundle);
			return frag;
		}

		@Override
		public Dialog onCreateDialog(Bundle savedInstanceState) {
			final int dialogId = getArguments().getInt("dialog_id");
			final int title = getArguments().getInt("title");
			final int message = getArguments().getInt("message");
			final String args = getArguments().getString("args");
			final boolean neutralOnly = getArguments().getBoolean("only_neutral");

			Builder dialog = new AlertDialog.Builder(getActivity())
			.setIcon(android.R.drawable.ic_dialog_alert)
			.setTitle(title)
			.setMessage(message)
			.setCancelable(false);

			if (neutralOnly) {
				dialog.setNeutralButton(android.R.string.ok, new OnClickListener() {
					@Override
					public void onClick(final DialogInterface alert, final int which) {
						((OnAlertClickInterface)getTargetFragment()).onAlertNeutralClick(dialogId, args);					
					}});
			} else {
				dialog.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int whichButton) {
						((OnAlertClickInterface)getTargetFragment()).onAlertPositiveClick(dialogId, args);
					}
				})
				.setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int whichButton) {
						((OnAlertClickInterface)getTargetFragment()).onAlertNegativeClick(dialogId, args);
					}
				});
			}
			return dialog.create();
		}
	}

	/* (non-Javadoc)
	 * @see org.openbmap.utils.OnAlertClickInterface#onAlertPositiveClick(int, java.lang.String)
	 */
	@Override
	public void onAlertPositiveClick(int alertId, String args) {
		if (alertId == ID_MULTIPLE_UPLOADS) {
			// just all pending
			int id = (args != null ? Integer.valueOf(args) : RadioBeacon.SESSION_NOT_TRACKING);
			stop(id);
			((SessionFragementListener) getSherlockActivity()).exportAllCommand();
		}
		
	}

	/* (non-Javadoc)
	 * @see org.openbmap.utils.OnAlertClickInterface#onAlertNegativeClick(int, java.lang.String)
	 */
	@Override
	public void onAlertNegativeClick(int alertId, String args) {
		if (alertId == ID_MULTIPLE_UPLOADS) {
			// just upload selected
			int id = (args != null ? Integer.valueOf(args) : RadioBeacon.SESSION_NOT_TRACKING);
			stop(id);
			((SessionFragementListener) getSherlockActivity()).exportCommand(id);
		}
		
	}

	/* (non-Javadoc)
	 * @see org.openbmap.utils.OnAlertClickInterface#onAlertNeutralClick(int, java.lang.String)
	 */
	@Override
	public void onAlertNeutralClick(int alertId, String args) {
		// TODO Auto-generated method stub
		
	}
}
