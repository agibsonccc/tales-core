package tales.scrapers;




import java.util.ArrayList;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;

import tales.config.Config;
import tales.services.Connection;
import tales.services.Document;
import tales.services.Download;
import tales.services.Logger;
import tales.services.TalesDB;
import tales.services.TalesException;
import tales.services.Task;
import tales.services.TasksDB;
import tales.system.AppMonitor;
import tales.system.TalesSystem;
import tales.templates.TemplateInterface;
import tales.workers.DefaultFailover;
import tales.workers.FailoverInterface;
import tales.workers.TaskWorker;




public class AttributeScraper{




	private static TalesDB talesDB;
	private static TasksDB tasksDB;
	private static long loopReferenceTime;
	private static TaskWorker taskWorker;



	
	public static void init(ScraperConfig scraperConfig, String attributeName, long loopReferenceTime) throws TalesException{


		try{
			
			
			AttributeScraper.loopReferenceTime = loopReferenceTime;

			
			// inits the services
			talesDB = new TalesDB(scraperConfig.getConnection(), scraperConfig.getTemplate().getMetadata());
			tasksDB = new TasksDB(scraperConfig);
			
			
			if(AttributeScraper.loopReferenceTime == 0){
				AttributeScraper.loopReferenceTime = talesDB.getMostRecentCrawledDocuments(1).get(0).getLastUpdate().getTime();
			}


			// starts the task machine with the template
			FailoverInterface failover = new DefaultFailover(Config.getFailover(scraperConfig.getTemplate().getMetadata().getDatabaseName()), AttributeScraper.loopReferenceTime);
			taskWorker = new TaskWorker(scraperConfig, failover);
			taskWorker.init();


			while(!taskWorker.hasFailover()){
				
				// adds tasks
				if((tasksDB.count() + taskWorker.getTasksRunning().size()) < Config.getMinTasks()){

					ArrayList<Task> tasks = getTasks(attributeName);

					if(tasks.size() > 0){

						Logger.log(new Throwable(), "adding tasks to \"" + scraperConfig.getTaskName() + "\"");

						tasksDB.add(tasks);

						if(!taskWorker.isWorkerActive() && !taskWorker.hasFailover()){
							taskWorker = new TaskWorker(scraperConfig, failover);
							taskWorker.init();
						}
					}

				}
				

				// if no tasks means we are finished
				if((tasksDB.count() + taskWorker.getTasksRunning().size()) == 0){
					break;
				}
				
				
				Thread.sleep(1000);
			}
			
			
			// deletes the server
			new Download().getURLContent("http://" + TalesSystem.getPublicDNSName() + ":" + Config.getDashbaordPort() + "/delete");


		}catch(Exception e){
			throw new TalesException(new Throwable(), e);
		}

	}




	private static ArrayList<Task> getTasks(String attributeName) throws TalesException{

		ArrayList<Task> tasks = new ArrayList<Task>();

		for(Document document : talesDB.getAndUpdateLastCrawledDocumentsWithAttribute(attributeName, Config.getMaxTasks())){

			// checks if the most recently crawled user is older than this new user, 
			// this means that the "most recent user" is now old and we have looped
			if(loopReferenceTime >= document.getLastUpdate().getTime()){

				Task task = new Task();
				task.setDocumentId(document.getId());
				task.setDocumentName(document.getName());

				tasks.add(task);

			}

		}

		return tasks;

	}




	public static void main(String[] args) throws TalesException {

		try{

			Options options = new Options();
			options.addOption("template", true, "template class path");
			options.addOption("attribute", true, "user attribute name");
			options.addOption("threads", true, "number of templates");
			options.addOption("loopReferenceTime", true, "loopReferenceTime");
			CommandLineParser parser = new PosixParser();
			CommandLine cmd = parser.parse(options, args);

			String templatePath = cmd.getOptionValue("template");
			String attributeName = cmd.getOptionValue("attribute");
			int threads = Integer.parseInt(cmd.getOptionValue("threads"));
			
			long loopReferenceTime = 0;
			if(cmd.hasOption("loopReferenceTime")){
				loopReferenceTime = Long.parseLong(cmd.getOptionValue("loopReferenceTime"));
			}


			// when app is killed
			Runtime.getRuntime().addShutdownHook(new Thread() {

				public void run() {

					if(taskWorker != null){
						taskWorker.stop();
					}

					Logger.log(new Throwable(), "---> bye...");

				}
			});


			// monitors the app performance
			AppMonitor.init();


			// reflection / new template
			TemplateInterface template = (TemplateInterface) Class.forName(templatePath).newInstance();


			// connection
			Connection connection = new Connection();
			connection.setConnectionsNumber(threads);
			
			
			// scraper config
			ScraperConfig scraperConfig = new ScraperConfig();
			scraperConfig.setScraperName("AttributeScraper");
			scraperConfig.setTemplate(template);
			scraperConfig.setConnection(connection);

			
			// scraper
			AttributeScraper.init(scraperConfig, attributeName, loopReferenceTime);


			// stop
			AppMonitor.stop();
			System.exit(0);
			

		}catch(Exception e){
			AppMonitor.stop();
			System.exit(0);
			throw new TalesException(new Throwable(), e);
		}

	}

}
