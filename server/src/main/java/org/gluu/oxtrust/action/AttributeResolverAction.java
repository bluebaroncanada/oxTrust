
package org.gluu.oxtrust.action;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;

import javax.enterprise.context.ConversationScoped;
import javax.faces.application.FacesMessage;
import javax.inject.Inject;
import javax.inject.Named;

import org.gluu.jsf2.message.FacesMessages;
import org.gluu.jsf2.service.ConversationService;
import org.gluu.oxtrust.ldap.service.AttributeService;
import org.gluu.oxtrust.util.OxTrustConstants;
import org.gluu.site.ldap.persistence.exception.LdapMappingException;
import org.slf4j.Logger;
import org.xdi.config.oxtrust.AppConfiguration;
import org.xdi.ldap.model.GluuStatus;
import org.xdi.model.GluuAttribute;
import org.xdi.model.GluuAttributeDataType;
import org.xdi.model.GluuUserRole;
import org.xdi.model.OxMultivalued;
import org.xdi.service.security.Secure;

@ConversationScoped
@Named("attributeResolverAction")
@Secure("#{permissionService.hasPermission('trust', 'access')}")
public class AttributeResolverAction implements Serializable {

	private static final long serialVersionUID = -9125609238796284572L;

	@Inject
	private Logger log;

	@Inject
	private AttributeService attributeService;

	@Inject
	private FacesMessages facesMessages;
	
	@Inject
	private ConversationService conversationService;
	
	@Inject
	private AppConfiguration applicationConfiguration;

	private GluuAttribute attribute = new GluuAttribute();
	private boolean enable;
	private String base;
	
	public boolean isEnable() {
		return enable;
	}
	public void setEnable(boolean enable) {
		this.enable = enable;
	}
	public GluuAttribute getAttribute() {
		return attribute;
	}
	public void setAttribute(GluuAttribute attribute) {
		this.attribute = attribute;
	}
	
	
	public String getBase() {
		return base;
	}

	public void setBase(String base) {
		this.base = base;
	}

	public String saveCustomAttributetoResolveImpl(){
		String outcome = saveCustomAttributetoResolve();
		
		if (OxTrustConstants.RESULT_SUCCESS.equals(outcome)) {
			facesMessages.add(FacesMessage.SEVERITY_INFO, "NameId configuration updated successfully");
		} else if (OxTrustConstants.RESULT_FAILURE.equals(outcome)) {
			facesMessages.add(FacesMessage.SEVERITY_ERROR, "Failed to update NameId configuration");
		}
		
		return outcome;
	}

	public String saveCustomAttributetoResolve(){
		if(!enable){
			return OxTrustConstants.RESULT_FAILURE;
		}
		String attributeName = this.attribute.getName();
		if(attributeService.getAttributeByName(attributeName) != null){
			facesMessages.add(FacesMessage.SEVERITY_ERROR, "Attribute already exists.");
			return OxTrustConstants.RESULT_FAILURE;
		}
		String inum = attributeService.generateInumForNewAttribute();
		
		String orgInum = applicationConfiguration.getOrgInum();
		String dn = "inum="+inum+",ou=attributes,o="+orgInum+",o=gluu";
		System.out.println(dn);
		this.attribute.setDataType(GluuAttributeDataType.STRING);
		GluuUserRole[] gluuEditRole = new GluuUserRole[]{GluuUserRole.ADMIN};
		this.attribute.setEditType(gluuEditRole);
		GluuUserRole[] gluuViewRole = new GluuUserRole[]{GluuUserRole.ADMIN, GluuUserRole.USER};
		this.attribute.setViewType(gluuViewRole);
		this.attribute.setOxMultivaluedAttribute(OxMultivalued.FALSE);
		this.attribute.setStatus(GluuStatus.ACTIVE);
		this.attribute.setInum(inum);
		this.attribute.setDisplayName(attributeName);
		this.attribute.setDn(dn);
		try {
		attributeService.addAttribute(this.attribute);
		} catch (LdapMappingException ex) {
			log.error("Failed to add new attribute {0}", this.attribute.getInum(), ex);

			facesMessages.add(FacesMessage.SEVERITY_ERROR, "Failed to add new attribute");
			return OxTrustConstants.RESULT_FAILURE;
		}
		
		BufferedReader br = null;
		FileReader fr = null;
		FileWriter fw = null;
		PrintWriter pw = null;
		String filePath = ""; //applicationConfiguration.getAttributeResolverPath();
		File fileRead = new File(filePath);
		File fileWrite = new File(filePath+".tmp");
		try {
			
			fr = new FileReader(fileRead);
			br = new BufferedReader(fr);
			
			fw = new FileWriter(fileWrite);
			pw = new PrintWriter(fw);
			String sCurrentLine;
			String lineSeperator = System.getProperty("line.separator");
			boolean disableEdit = false;
			boolean modified = false;
			
			while ((sCurrentLine = br.readLine()) != null) {
				if(sCurrentLine.contains("id=\""+attributeName+"\"")){
					break;
				}
				
				if(sCurrentLine.contains("#if($attribute.")){
					disableEdit = true;
				}
				
				if(sCurrentLine.trim().equals("#else")){
					disableEdit = false;
				}
				if(sCurrentLine.trim().equals("#end")){
					if(!disableEdit){
						if(!modified){
							String attributeText = "";
								attributeText = "#if($attribute.name.equals('"+attributeName+"'))"+ lineSeperator+lineSeperator+								
										"\t<resolver:AttributeDefinition xsi:type=\"ad:Simple\" id=\""+attributeName+"\" sourceAttributeID=\""+this.getBase()+"\">"+
										 lineSeperator + "\t\t<resolver:Dependency ref=\"siteLDAP\"/>" + lineSeperator +
										 "\t\t<resolver:AttributeEncoder xsi:type=\"SAML2StringNameID\" xmlns=\"urn:mace:shibboleth:2.0:attribute:encoder\" nameFormat=\"urn:oasis:names:tc:SAML:2.0:nameid-format:"+this.attribute.getNameIdType()+"\" />"+
										 lineSeperator+"\t</resolver:AttributeDefinition>"+lineSeperator+"#end"+lineSeperator;
									
									pw.println(sCurrentLine+lineSeperator+attributeText);
									modified = true;
						} else {
							pw.println(sCurrentLine);
						}
					}else{
						pw.println(sCurrentLine);
					}
				}else{
					pw.println(sCurrentLine);
				}
			}

		} catch (IOException e) {

			e.printStackTrace();

		} finally {

			try {

				if (br != null)
					br.close();

				if (fr != null)
					fr.close();
				
				if (pw != null)
					pw.close();
				
				if (fw != null)
					fw.close();

			} catch (IOException ex) {

				ex.printStackTrace();

			}

		}
		
		if(fileRead.exists()){
			fileRead.delete();
			fileWrite.renameTo(fileRead);
		}

		return OxTrustConstants.RESULT_SUCCESS;
	}
	
	public String cancel(){
		facesMessages.add(FacesMessage.SEVERITY_INFO, "Saml NameId configuration not updated");
		conversationService.endConversation();

		return OxTrustConstants.RESULT_SUCCESS;
	}
}