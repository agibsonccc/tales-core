package tales.templates;




import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;

import tales.services.Connection;
import tales.services.Logger;
import tales.services.Mongo;
import tales.services.Solr;
import tales.services.TalesDB;
import tales.services.TalesException;
import tales.services.TasksDB;
import tales.system.AppMonitor;




public class TemplateDataRemover {




	public static void remove(TemplateMetadataInterface metadata){
		
		
		Logger.log(new Throwable(), "removing data");

		
		// DB
		try{
			TalesDB talesDB = new TalesDB(new Connection(), metadata);
			talesDB.deleteAll();
		}catch(Exception e){
			new TalesException(new Throwable(), e);
		}


		// tasks
		try{
			TasksDB.deleteTaskTablesFromDomain(metadata.getNamespace());
		}catch(Exception e){
			new TalesException(new Throwable(), e);
		}
		
		
		// mongo
		try{
			Mongo mongo = new Mongo(metadata);
			mongo.delete();
		}catch(Exception e){
			new TalesException(new Throwable(), e);
		}
		
		
		// solr
		try{
			Solr solr = new Solr(metadata);
			solr.delete();
		}catch(Exception e){
			new TalesException(new Throwable(), e);
		}
		
		
		Logger.log(new Throwable(), "finished removing the data");

	}




	public static void main(String[] args) {

		try{


			Options options = new Options();
			options.addOption("template", true, "template class path");
			CommandLineParser parser = new PosixParser();
			CommandLine cmd = parser.parse(options, args);

			String templatePath = cmd.getOptionValue("template");


			// reflection / new template
			TemplateInterface template = (TemplateInterface) Class.forName(templatePath).newInstance();


			// monitors the app performance
			AppMonitor.init();


			// data remover
			TemplateDataRemover.remove(template.getMetadata());


			// stop
			AppMonitor.stop();
			System.exit(0);


		}catch(Exception e){
			AppMonitor.stop();
			new TalesException(new Throwable(), e);
			System.exit(0);
		}

	}

}
