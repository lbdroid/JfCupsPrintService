package ml.rabidbeaver.cupsprint;

import java.util.List;
import java.util.concurrent.TimeUnit;

import ml.rabidbeaver.tasks.CancelJobTask;
import ml.rabidbeaver.tasks.GetPrinterListener;
import ml.rabidbeaver.tasks.GetPrinterTask;

import org.cups4j.CupsClient;
import org.cups4j.CupsPrintJobAttributes;
import org.cups4j.CupsPrinter;
import org.cups4j.WhichJobsEnum;
import org.cups4j.operations.AuthInfo;

import ml.rabidbeaver.cupsprintservice.R;

import android.os.Bundle;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListView;
import android.widget.TextView;

public class JobListActivity extends Activity implements GetPrinterListener{
	
	private PrintQueueConfig config;
	private Updater updater;
	private JobRecordAdapter recordAdapter;
	private TextView jobPrinter;
	private ListView jobsListView;
	private CupsClient client;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_job_list);
		Intent intent = getIntent();
		String nickname = intent.getStringExtra("printer");
		if (nickname == null){
			Util.showToast(this, "No printer selected");
			finish();
			return;
		}
		PrintQueueIniHandler ini = new PrintQueueIniHandler(getBaseContext());
		config = ini.getPrinter(nickname);
		if (config == null){
			Util.showToast(this, "Printer config not found");
			finish();
			return;
		}
		jobPrinter = (TextView) findViewById(R.id.jobPrinter);
		jobsListView = (ListView) findViewById(R.id.jobsListView);
		recordAdapter = new JobRecordAdapter(this);
		jobsListView.setAdapter(recordAdapter);
		try {
			client = new CupsClient(Util.getClientURL(config));
		} catch (Exception e){
			Util.showToast(this, e.toString());
			finish();
			return;
		}
		client.setUserName(config.userName);
		AuthInfo auth = null;
		if (!(config.password.equals(""))){
			auth = new AuthInfo(getBaseContext(), config.userName, config.password);
		}
		GetPrinterTask task = new GetPrinterTask(client, auth, config.getPrintQueue(), false);
		task = new GetPrinterTask(client, auth, Util.getQueue(config),true);
		task.setListener(this);
		try {
			task.execute().get(5000, TimeUnit.MILLISECONDS);
		}
		catch (Exception e){
			Util.showToast(this, e.toString());
			finish();
			return;
		}
		Exception ex = task.getException();
		if (ex != null){
			Util.showToast(this, ex.toString());
			finish();
			return;
		}
		jobsListView.setOnItemClickListener(new OnItemClickListener() {
        public void onItemClick(AdapterView<?> parent, View view,
                int position, long id) {
        	CupsPrintJobAttributes record = (CupsPrintJobAttributes) recordAdapter.getItem(position);
        	setOperation(record);
        	}
		});

		
	}

	@Override
	protected void onPause(){
		super.onPause();
		if (updater != null){
			updater.doStop();
		}
		updater = null;
	}
	
	@Override
	protected void onResume(){
		super.onResume();
		updater = new Updater(this);
		new Thread(updater).start();;
	}
		

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.aboutmenu, menu);
		return true;
	}
	
	@Override
	  public boolean onOptionsItemSelected(MenuItem item) {
	    switch (item.getItemId()) {
	    	case R.id.about:
	    		Intent intent = new Intent(this, AboutActivity.class);
	    		intent.putExtra("printer", "");
	    		startActivity(intent);
	    		break;
	    }
	    return super.onContextItemSelected(item);
	}
	
	public void setOperation(final CupsPrintJobAttributes record){
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
	    String jobId = String.valueOf(record.getJobID());
	    String[] items = {"Cancel Job", "Hold Job", "Release Job"}; 
	    builder.setTitle("Job Id: " + jobId)
	           .setItems(items, new DialogInterface.OnClickListener() {
	               public void onClick(DialogInterface dialog, int which) {
	            	   doOperation(record, which);
	           }
	    });
	    AlertDialog dialog = builder.create();
	    dialog.show();
	}
	
	public void doOperation(CupsPrintJobAttributes record, int operation){
	    AuthInfo auth = null;
	    if (!(config.password.equals(""))){
	    	auth = new AuthInfo(getBaseContext(), config.userName, config.password);
	    }
	    CancelJobTask.Operation taskOp = null;
	    switch (operation){
	    	case 0:
	    		taskOp = CancelJobTask.Operation.CANCEL;
	    		break;
	    	case 1:
	    		taskOp = CancelJobTask.Operation.HOLD;
	    		break;
	    	case 2:
	    		taskOp = CancelJobTask.Operation.RELEASE;
	    		break;
	    }
	    if (taskOp != null){
	    	CancelJobTask task = 
	    			new CancelJobTask(this, client, auth, taskOp, record.getJobID());
	    	task.execute();
	    }
	}
	
	public void updateUI(final List<CupsPrintJobAttributes> records){
		
		runOnUiThread(new Runnable(){
			
			public void run() {
				jobPrinter.setText(config.nickname + ": " + records.size() + " " + "jobs");
				recordAdapter.setRecords(records);
				recordAdapter.notifyDataSetChanged();
			}
		});
	}
	
	public class Updater implements Runnable{
		
		private boolean stop = false;
		private Activity activity;
		
		public Updater(Activity activity){
			this.activity = activity;
		}
		
		public void doStop(){
			stop = true;
		}

		@Override
		public void run() {
			if (client == null){
				return;
			}
			int passes = 0;
			while (!stop){
				if (passes == 0){
					AuthInfo auth = null;
					if (!config.password.equals("")){
						auth = new AuthInfo(getBaseContext(), config.userName, config.password);
					}
					List<CupsPrintJobAttributes> jobList;
					try {
						jobList = 
								client.getJobs(config.getQueuePath(), auth, WhichJobsEnum.NOT_COMPLETED, false);
					}
					catch (Exception e){
						Util.showToast(activity, "CupsPrintService Jobs List\n" + e.toString());
						activity.finish();
						return;
					}
					if (!stop){
						updateUI(jobList);
					}
				}
				passes++;
				if (passes > 3){
					passes = 0;
				}
				try {
					Thread.sleep(1000);
				}catch (Exception e){
					return;
				}
				
			}
			
		}
		
	}

	@Override
	public void onGetPrinterTaskDone(CupsPrinter printer, Exception exception) {
		// do nothing
	}

}