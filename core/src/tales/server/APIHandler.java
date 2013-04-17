package tales.server;




import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.sf.json.JSONArray;
import net.sf.json.JSONSerializer;
import net.sf.json.JSONObject;

import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

import tales.config.Config;
import tales.config.Globals;
import tales.s3.S3DBBackup;
import tales.services.Download;
import tales.services.Log;
import tales.services.Logger;
import tales.services.LogsDB;
import tales.services.TalesException;
import tales.system.AppMonitor;
import tales.system.TalesSystem;
import tales.templates.TemplateLocalhostConnection;
import tales.utils.DBUtils;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;




public class APIHandler extends AbstractHandler{




	public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {


		baseRequest.setHandled(true);


		// cross domain
		response.setHeader("Access-Control-Allow-Origin", "*"); 


		if(target.equals("/reboot")){
			reboot();


		}else if(target.equals("/branches")){
			branches(response);


		}else if(target.startsWith("/start")){
			start(target, response);


		}else if(target.equals("/new")){
			newServer(request, response);


		}else if(target.equals("/delete")){
			delete(response);


		}else if(target.equals("/force-delete")){
			forceDelete(response);


		}else if(target.equals("/kill")){
			kill(request);


		}else if(target.equals("/solr")){
			solr(response);


		}else if(target.equals("/errors")){
			errors(response);


		}else if(target.equals("/scale")){
			scale(request, response);


		}else if(target.equals("/databases")){
			databases(response);
		}

	}




	private void reboot(){

		try{


			Logger.log(new Throwable(), "REBOOT: rebooting...");
			ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", "reboot");
			builder.start();


		}catch(Exception e){
			new TalesException(new Throwable(), e);
		}

	}




	private void branches(HttpServletResponse response){

		try{


			String command = "cd " + Globals.ENVIRONMENTS_CONFIG_DIR + " && git branch";

			ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", command);
			Process process = builder.start();
			process.waitFor();

			String output = IOUtils.toString(process.getInputStream(), "utf-8");

			process.destroy();

			// http response
			response.setContentType("text/plain");
			response.setStatus(HttpServletResponse.SC_OK);
			response.getWriter().println(output);


		} catch (Exception e) {
			new TalesException(new Throwable(), e);
		}

	}




	private void start(String target, HttpServletResponse response){

		try{


			String command = "java -cp " + Config.getJarPath() + " " + target.replace("/start/", "") + " >/dev/null 2>&1";
			Logger.log(new Throwable(), "START: launching \"" + command + "/");

			ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", command);
			builder.start();


		}catch(Exception e){
			new TalesException(new Throwable(), e);
		}

	}




	private String newServer(HttpServletRequest request, HttpServletResponse response){

		try {


			AppMonitor.init();


			String publicDNS = null;
			Logger.log(new Throwable(), "NEW: creating new server");


			// aws server
			if(TalesSystem.isThisAnAWSServer()){


				// instance type
				String instanceType = request.getParameter("instanceType");
				if(instanceType == null){
					instanceType = Config.getAWSInstanceType();
				}


				// ec2
				AmazonEC2 ec2 = new AmazonEC2Client(new BasicAWSCredentials(Config.getAWSAccessKeyId(), Config.getAWSSecretAccessKey()));
				ec2.setEndpoint(Config.getAWSEndpoint());

				RunInstancesRequest ec2Request = new RunInstancesRequest();
				ec2Request.withImageId(Config.getAWSAMI());
				ec2Request.withInstanceType(instanceType);
				ec2Request.withMinCount(1);
				ec2Request.withMaxCount(1);
				ec2Request.withSecurityGroupIds(Config.getAWSSecurityGroup());


				// creates the server
				RunInstancesResult runInstancesRes = ec2.runInstances(ec2Request);
				String instanceId = runInstancesRes.getReservation().getInstances().get(0).getInstanceId();


				// waits for the instance to be ready
				while(true){

					try{

						DescribeInstancesRequest describeInstancesRequest = new DescribeInstancesRequest();
						describeInstancesRequest.setInstanceIds(Collections.singletonList(instanceId));

						DescribeInstancesResult describeResult = ec2.describeInstances(describeInstancesRequest);
						List <Reservation> list  = describeResult.getReservations();

						// when ready
						publicDNS = list.get(0).getInstances().get(0).getPublicDnsName();					
						if(list.get(0).getInstances().get(0).getState().getCode() != 0 && publicDNS != null){
							break;
						}

					}catch(Exception e){}

					Thread.sleep(100);
				}


				// rackspace server
			}else{


				// gets auth token
				URL url = new URL("https://auth.api.rackspacecloud.com/v1.0");
				HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
				conn.setRequestProperty("X-Auth-Key", Config.getRackspaceKey());
				conn.setRequestProperty("X-Auth-User", Config.getRackspaceUsername());
				String token = conn.getHeaderField("X-Auth-Token");
				conn.disconnect();


				// server obj
				JSONObject json = new JSONObject();
				JSONObject serverJSON = new JSONObject();
				serverJSON.put("imageId", Config.getRackspaceImageId());
				serverJSON.put("flavorId", Config.getRackspaceFlavor());
				serverJSON.put("name", "tales-node");
				json.put("server", serverJSON);


				// new server
				url = new URL("https://servers.api.rackspacecloud.com/v1.0/" + Config.getRackspaceAccount() + "/servers");
				conn = (HttpsURLConnection) url.openConnection();
				conn.setRequestProperty("X-Auth-Token", token);
				conn.setRequestProperty("Content-type", "application/json");
				conn.setDoOutput(true);

				OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream());
				writer.write(json.toString());
				writer.flush();

				String content = IOUtils.toString(conn.getInputStream());
				JSONObject result = (JSONObject) JSONSerializer.toJSON(content);
				publicDNS = result.getJSONObject("server").getJSONObject("addresses").getJSONArray("public").get(0).toString();

				conn.disconnect();

			}


			Logger.log(new Throwable(), "NEW: finished creating server (" + publicDNS + ")");
			Logger.log(new Throwable(), "NEW: waiting for server (" + publicDNS + ") to be up...");


			// waits for tales dashboard to be up
			while(true){

				if(new Download().urlExists("http://" + publicDNS + ":" + Config.getDashbaordPort())){
					break;
				}

				Thread.sleep(1000);

			}


			Logger.log(new Throwable(), "NEW: finished");


			AppMonitor.stop();


			// http response
			JSONObject json = new JSONObject();
			json.put("dns", publicDNS);

			response.setContentType("application/json");
			response.setStatus(HttpServletResponse.SC_OK);
			response.getWriter().println(json);


			return publicDNS;


		} catch (Exception e) {
			new TalesException(new Throwable(), e);
			return null;
		}

	}




	private void delete(HttpServletResponse response){

		try {


			// makes sure that we dont delete a server with dbs -- we ignore tales logs
			if(DBUtils.getLocalTalesDBNames().size() == 0 || 
					(DBUtils.getLocalTalesDBNames().size() == 1 && DBUtils.getLocalTalesDBNames().get(0).contains(LogsDB.getDBName()))){

				forceDelete(response);

			}else{
				Logger.log(new Throwable(), "DELETE: cant delete server, it contains tales databases. Delete all the tales databases before trying to delete the server.");
			}


		} catch (Exception e) {
			new TalesException(new Throwable(), e);
		}

	}




	private void forceDelete(HttpServletResponse response){

		try {

			
			// aws server
			if(TalesSystem.isThisAnAWSServer()){

				AmazonEC2 ec2 = new AmazonEC2Client(new BasicAWSCredentials(Config.getAWSAccessKeyId(), Config.getAWSSecretAccessKey()));
				TerminateInstancesRequest terminate = new TerminateInstancesRequest();
				terminate.getInstanceIds().add(TalesSystem.getAWSInstanceMetadata().getInstanceId());
				ec2.terminateInstances(terminate);


				// rackspace server
			}else{


				// gets auth token
				URL url = new URL("https://auth.api.rackspacecloud.com/v1.0");
				HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
				conn.setRequestProperty("X-Auth-Key", Config.getRackspaceKey());
				conn.setRequestProperty("X-Auth-User", Config.getRackspaceUsername());
				String token = conn.getHeaderField("X-Auth-Token");
				conn.disconnect();


				// lists all servers available
				url = new URL("https://servers.api.rackspacecloud.com/v1.0/" + Config.getRackspaceAccount() + "/servers/detail");
				conn = (HttpsURLConnection) url.openConnection();
				conn.setRequestProperty("X-Auth-Token", token);
				conn.setRequestProperty("Content-type", "application/json");
				conn.setDoOutput(true);

				String content = IOUtils.toString(conn.getInputStream());
				JSONObject result = (JSONObject) JSONSerializer.toJSON(content);
				JSONArray servers = result.getJSONArray("servers");


				for(int i = 0; i < servers.size(); i++){


					String publicDNS = servers.getJSONObject(i).getJSONObject("addresses").getJSONArray("public").getString(0);
					int serverId = servers.getJSONObject(i).getInt("id");


					if(publicDNS.equals(TalesSystem.getPublicDNSName())){

						// delete server
						url = new URL("https://servers.api.rackspacecloud.com/v1.0/" + Config.getRackspaceAccount() + "/servers/" + serverId);
						conn = (HttpsURLConnection) url.openConnection();
						conn.setRequestMethod("DELETE");
						conn.setRequestProperty("X-Auth-Token", token);
						conn.setRequestProperty("Content-type", "application/json");
						conn.getInputStream();

					}
				}

				conn.disconnect();

			}


			Logger.log(new Throwable(), "DELETE: server deleted");


		} catch (Exception e) {
			new TalesException(new Throwable(), e);
		}

	}




	private void kill(HttpServletRequest request){

		try {


			String pid = request.getParameter("pid");

			Logger.log(new Throwable(), "KILL: killing pid: " + pid);
			ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", "kill -9 " + pid);
			builder.start();


		} catch (Exception e) {
			new TalesException(new Throwable(), e);
		}

	}




	@SuppressWarnings("unchecked")
	private void solr(HttpServletResponse response) {

		try{

			
			// waits for solr to be ready
			String data = null;
			String url = "http://" + TalesSystem.getPublicDNSName() + ":" + Config.getSolrPort() + "/solr/admin/cores?wt=json";

			while(true){

				try{

					Download download = new Download();
					if(download.urlExists(url)){
						data = download.getURLContent(url);
						break;
					}

				}catch(Exception e){}

				Thread.sleep(100);

			}

			JSONObject json = (JSONObject) JSONSerializer.toJSON(data);

			JSONArray array = new JSONArray();
			Set<String> cores = json.getJSONObject("status").keySet();
			Iterator<String> it = cores.iterator();

			while (it.hasNext()) {

				String core = it.next();

				if(!core.equals("")){

					JSONObject obj = new JSONObject();
					obj.put(core, "http://" + TalesSystem.getPublicDNSName() + ":" + Config.getSolrPort() + "/solr/" + core + "/select/?q=*:*");

					array.add(obj);

				}
			} 


			// response
			response.setContentType("application/json");
			response.setStatus(HttpServletResponse.SC_OK);
			response.getWriter().println(array);


		} catch (Exception e) {
			new TalesException(new Throwable(), e);
		}

	}




	private void errors(HttpServletResponse response) {

		try{


			// gets the errors
			JSONArray array = new JSONArray();

			for(Log log : LogsDB.getErrors(Globals.DASHBOARD_MAX_ERRORS)){

				JSONObject obj = new JSONObject();
				obj.put("added", log.getAdded().toString());
				obj.put("id", log.getId());
				obj.put("publicDNS", log.getPublicDNS());
				obj.put("pid", log.getPid());
				obj.put("logType", log.getLogType());
				obj.put("methodPath", log.getMethodPath());
				obj.put("lineNumber", log.getLineNumber());

				array.add(obj);
			}


			// response
			response.setContentType("application/json");
			response.setStatus(HttpServletResponse.SC_OK);
			response.getWriter().println(array);


		} catch (Exception e) {
			new TalesException(new Throwable(), e);
		}

	}




	public void scale(HttpServletRequest request, HttpServletResponse response){

		try{


			AppMonitor.init();

			// backsups the databases
			Logger.log(new Throwable(), "SCALE: backing up databases into s3 bucket: " + Globals.SCALE_TEMP_S3_BUCKET_NAME);
			S3DBBackup.backupAllExcept(Globals.SCALE_TEMP_S3_BUCKET_NAME, null);

			// creates a new server
			String publicDNS = newServer(request, response);

			// moves the backups to the new server
			Logger.log(new Throwable(), "SCALE: restoring databases into new server");
			String dbNames = DBUtils.getLocalTalesDBNames().toString().replace(" ", "");
			dbNames = dbNames.substring(1, dbNames.length() - 1);
			String url = "http://" + publicDNS + ":" + Config.getDashbaordPort() + "/start/tales.s3.S3DBRestore -bucket " + Globals.SCALE_TEMP_S3_BUCKET_NAME + " -db_names " + dbNames;
			Download download = new Download();
			download.getURLContent(url);

			Logger.log(new Throwable(), "SCALE: remember to edit the config file so it matches the new host url.");

			AppMonitor.stop();

			forceDelete(response);


		} catch (Exception e) {
			new TalesException(new Throwable(), e);
		}

	}




	private void databases(HttpServletResponse response) {

		try{


			JSONArray json = new JSONArray();

			for(String dbName : DBUtils.getLocalTalesDBNames()){

				JSONArray tables = new JSONArray();
				for(String tableName : DBUtils.getTableNames(new TemplateLocalhostConnection(), dbName)){

					JSONObject table = new JSONObject();
					table.put("table", tableName);
					table.put("size", DBUtils.getTableCount(new TemplateLocalhostConnection(), dbName, tableName));
					tables.add(table);

				}

				JSONObject database = new JSONObject();
				database.put("name", dbName);
				database.put("tables", tables);
				json.add(database);

			}


			response.setContentType("application/json");
			response.setStatus(HttpServletResponse.SC_OK);
			response.getWriter().println(json);


		} catch (Exception e) {
			new TalesException(new Throwable(), e);
		}

	}

}