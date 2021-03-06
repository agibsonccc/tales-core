package tales.aws;




import tales.services.TalesDB;
import tales.services.Task;
import tales.templates.TemplateCommon;
import tales.templates.TemplateMetadataInterface;




public class AWSDataDownloader extends TemplateCommon{




	@Override
	public TemplateMetadataInterface getMetadata() {
		
		if(this.getTemplateConfig() == null){
			return null;
		}
		
		return this.getTemplateConfig().getTemplateMetadata();
		
	}




	@Override
	protected void process(TalesDB talesDB, Task task, String url, org.jsoup.nodes.Document doc) throws Exception{

		S3 s3 = new S3();
		s3.addTemplateDoc(this.getTemplateConfig().getTemplateMetadata(), url, doc);
		
	}




	@Override
	public boolean isTaskInvalid(Task task) {
		return false;
	}

}