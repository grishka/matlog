package com.pluscubed.logcat.helper;

import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.view.View;
import android.widget.Toast;

import com.pluscubed.logcat.R;
import com.pluscubed.logcat.util.UtilLogger;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/* 
 * Starting in JellyBean, the READ_LOGS permission must be requested as super user
 * or else you can only read your own app's logs.
 * 
 * This class contains helper methods to correct the problem.
 */
public class SuperUserHelper {
	
	private static final Pattern PID_PATTERN = Pattern.compile("\\d+");
	private static final Pattern SPACES_PATTERN = Pattern.compile("\\s+");
    private static UtilLogger log = new UtilLogger(SuperUserHelper.class);
    private static boolean failedToObtainRoot = false;

    private static void showWarningDialog(final Context context) {
		Handler handler = new Handler(Looper.getMainLooper());

		handler.post(new Runnable(){

			@Override
			public void run() {
				final String command=String.format("adb shell pm grant %s android.permission.READ_LOGS", context.getPackageName());
				AlertDialog dlg=new AlertDialog.Builder(context)
						.setTitle(R.string.no_logs_warning_title)
						.setMessage(Html.fromHtml(context.getString(R.string.no_logs_warning, context.getApplicationInfo().nonLocalizedLabel, command)))
						.setPositiveButton(android.R.string.ok, null)
						.setNeutralButton(R.string.copy_command, null)
						.show();
				// set the listener like this so the dialog doesn't dismiss when the button is clicked
				dlg.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener(){
					@Override
					public void onClick(View v){
						((ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE)).setText(command);
						Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
					}
				});
			}});
	}

	private static boolean haveReadLogsPermission(Context context){
		return context.getPackageManager().checkPermission("android.permission.READ_LOGS", context.getPackageName())==PackageManager.PERMISSION_GRANTED;
	}
	
	private static List<Integer> getAllRelatedPids(final int pid) {
	    List<Integer> result = new ArrayList<Integer>(Arrays.asList(pid));
	    // use 'ps' to get this pid and all pids that are related to it (e.g. spawned by it)
        try {
                
            final Process suProcess = Runtime.getRuntime().exec("su");
    
            new Thread(new Runnable() {
                
                @Override
                public void run() {
                    PrintStream outputStream = null;
                    try {
                        outputStream = new PrintStream(new BufferedOutputStream(suProcess.getOutputStream(), 8192));
                        outputStream.println("ps");
                        outputStream.println("exit");
                        outputStream.flush();
                    } finally {
                        if (outputStream != null) {
                            outputStream.close();
                        }
                    }
                    
                }
            }).run();
            
            if (suProcess != null) {
                try {
                    suProcess.waitFor();
                } catch (InterruptedException e) {
                    log.e(e, "cannot get pids");
                }
            }
            
            
            BufferedReader bufferedReader = null;
            try {
                bufferedReader = new BufferedReader(new InputStreamReader(suProcess.getInputStream()), 8192);
                while (bufferedReader.ready()) {
                    String[] line = SPACES_PATTERN.split(bufferedReader.readLine());
                    if (line.length >= 3 ) {
                        try {
                            if (pid == Integer.parseInt(line[2])) {
                                result.add(Integer.parseInt(line[1]));
                            }
                        } catch (NumberFormatException ignore) {}
                    }
                }
            } finally {
                if (bufferedReader != null) {
                    bufferedReader.close();
                }
            }
        } catch (IOException e1) {
            log.e(e1, "cannot get process ids");
        }
        
        return result;
	}
	
	public static void destroy(Process process) {
	    // stupid method for getting the pid, but it actually works
	    Matcher matcher = PID_PATTERN.matcher(process.toString());
	    matcher.find();
	    int pid = Integer.parseInt(matcher.group());
	    List<Integer> allRelatedPids = getAllRelatedPids(pid);
	    log.d("Killing %s", allRelatedPids);
	    for (Integer relatedPid : allRelatedPids) {
	        destroyPid(relatedPid);
	    }
        
	}
	
	private static void destroyPid(int pid) {

        Process suProcess = null;
        PrintStream outputStream = null;
        try {
            suProcess = Runtime.getRuntime().exec("su");
            outputStream = new PrintStream(new BufferedOutputStream(suProcess.getOutputStream(), 8192));
            outputStream.println("kill " + pid);
            outputStream.println("exit");
            outputStream.flush();
        } catch (IOException e) {
            log.e(e, "cannot kill process " + pid);
        } finally {
            if (outputStream != null) {
                outputStream.close();
            }
            if (suProcess != null) {
                try {
                    suProcess.waitFor();
                } catch (InterruptedException e) {
                    log.e(e, "cannot kill process " + pid);
                }
            }
        }
	}
	
	public static void requestRoot(final Context context) {
		Handler handler = new Handler(Looper.getMainLooper());
		Runnable toastRunnable=new Runnable(){
			@Override
			public void run() {
				Toast.makeText(context, R.string.toast_request_root, Toast.LENGTH_LONG).show();
			}};
		handler.postDelayed(toastRunnable, 200);
		
		Process process = null;
		try {
			// Preform su to get root privileges
			process = Runtime.getRuntime().exec("su");

			// confirm that we have root
			DataOutputStream outputStream = new DataOutputStream(process.getOutputStream());
			outputStream.writeBytes("echo hello\n");

			// Close the terminal
			outputStream.writeBytes("exit\n");
			outputStream.flush();

			process.waitFor();
			if (process.exitValue() != 0) {
				if(!haveReadLogsPermission(context))
					showWarningDialog(context);
				failedToObtainRoot = true;
			} else {
				// success
                PreferenceHelper.setJellybeanRootRan(context);
			}

		} catch (IOException e) {
			log.w(e, "Cannot obtain root");
			if(!haveReadLogsPermission(context))
				showWarningDialog(context);
			failedToObtainRoot = true;
		} catch (InterruptedException e) {
			log.w(e, "Cannot obtain root");
			if(!haveReadLogsPermission(context))
				showWarningDialog(context);
			failedToObtainRoot = true;
		}
		handler.removeCallbacks(toastRunnable);
	}
	
	public static boolean isFailedToObtainRoot() {
		return failedToObtainRoot;
	}
}
