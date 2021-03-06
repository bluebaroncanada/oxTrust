/*
 * oxTrust is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */

package org.gluu.oxtrust.action;

import java.io.IOException;
import java.io.Serializable;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.annotation.PreDestroy;
import javax.enterprise.context.ConversationScoped;
import javax.faces.application.FacesMessage;
import javax.inject.Inject;
import javax.inject.Named;
import javax.mail.AuthenticationFailedException;
import javax.mail.MessagingException;

import org.apache.commons.beanutils.PropertyUtils;
import org.gluu.jsf2.message.FacesMessages;
import org.gluu.jsf2.service.ConversationService;
import org.gluu.oxtrust.config.ConfigurationFactory;
import org.gluu.oxtrust.ldap.service.AppInitializer;
import org.gluu.oxtrust.ldap.service.ApplianceService;
import org.gluu.oxtrust.ldap.service.ImageService;
import org.gluu.oxtrust.ldap.service.OrganizationService;
import org.gluu.oxtrust.model.GluuAppliance;
import org.gluu.oxtrust.model.GluuCustomPerson;
import org.gluu.oxtrust.model.GluuOrganization;
import org.gluu.oxtrust.util.MailUtils;
import org.gluu.oxtrust.util.OxTrustConstants;
import org.gluu.site.ldap.persistence.exception.LdapMappingException;
import org.richfaces.event.FileUploadEvent;
import org.richfaces.model.UploadedFile;
import org.slf4j.Logger;
import org.xdi.config.oxtrust.AppConfiguration;
import org.xdi.model.GluuImage;
import org.xdi.model.SmtpConfiguration;
import org.xdi.service.security.Secure;
import org.xdi.util.StringHelper;

/**
 * Action class for configuring application
 * 
 * @author Yuriy Movchan Date: 11.16.2010
 */
@Named("updateOrganizationAction")
@ConversationScoped
@Secure("#{permissionService.hasPermission('configuration', 'access')}")
public class UpdateOrganizationAction implements Serializable {

	private static final long serialVersionUID = -4470460481895022468L;

	@Inject
	private Logger log;

	@Inject
	private FacesMessages facesMessages;

	@Inject
	private ConversationService conversationService;

	@Inject
	private GluuCustomPerson currentPerson;

	@Inject
	private ImageService imageService;

	@Inject
	private OrganizationService organizationService;

	@Inject
	private ApplianceService applianceService;

	@Inject
	private ConfigurationFactory configurationFactory;

	@Inject
	private AppConfiguration appConfiguration;

	@Inject
	private AppInitializer appInitializer;

	private GluuOrganization organization;

	protected GluuImage oldLogoImage, curLogoImage;
	protected String loginPageCustomMessage;
	protected String welcomePageCustomMessage;
	protected String welcomeTitleText;

	private String buildDate;
	private String buildNumber;

	private GluuImage curFaviconImage, oldFaviconImage;

	private GluuAppliance appliance;
	
	private List<GluuAppliance> appliances;

	private boolean initialized;

	public String modify()  {
		if (this.initialized) {
			return OxTrustConstants.RESULT_SUCCESS;
		}

		String resultOrganization = modifyOrganization();
		String resultApplliance = modifyApplliance();

		if (!StringHelper.equals(OxTrustConstants.RESULT_SUCCESS, resultOrganization)
				|| !StringHelper.equals(OxTrustConstants.RESULT_SUCCESS, resultApplliance)) {
			facesMessages.add(FacesMessage.SEVERITY_ERROR, "Failed to prepare for organization configuration update");
			conversationService.endConversation();

			return OxTrustConstants.RESULT_FAILURE;
		}

		this.initialized = true;

		return OxTrustConstants.RESULT_SUCCESS;
	}

	private String modifyOrganization()  {
		if (this.organization != null) {
			return OxTrustConstants.RESULT_SUCCESS;
		}

		try {
			GluuOrganization tmpOrganization = organizationService.getOrganization();
			this.organization = new GluuOrganization();

			// Clone shared instance
			try {
				PropertyUtils.copyProperties(this.organization, tmpOrganization);
			} catch (Exception ex) {
				log.error("Failed to load organization", ex);
				this.organization = null;
			}
		} catch (LdapMappingException ex) {
			log.error("Failed to load organization", ex);
		}

		if (this.organization == null) {
			return OxTrustConstants.RESULT_FAILURE;
		}

		initLogoImage();
		initFaviconImage();

		this.loginPageCustomMessage = organizationService.getOrganizationCustomMessage(OxTrustConstants.CUSTOM_MESSAGE_LOGIN_PAGE);
		this.welcomePageCustomMessage = organizationService.getOrganizationCustomMessage(OxTrustConstants.CUSTOM_MESSAGE_WELCOME_PAGE);
		this.welcomeTitleText = organizationService.getOrganizationCustomMessage(OxTrustConstants.CUSTOM_MESSAGE_TITLE_TEXT);

		appliances = new ArrayList<GluuAppliance>();
		try {
			appliances.addAll(applianceService.getAppliances());
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		
		return OxTrustConstants.RESULT_SUCCESS;
	}

	private void initLogoImage() {
		this.oldLogoImage = imageService.getGluuImageFromXML(this.organization.getLogoImage());
		if (this.oldLogoImage != null) {
			this.oldLogoImage.setLogo(true);
		}
		this.curLogoImage = this.oldLogoImage;
	}

	private void initFaviconImage() {
		this.oldFaviconImage = imageService.getGluuImageFromXML(this.organization.getFaviconImage());
		this.curFaviconImage = this.oldFaviconImage;
	}

	public String save() {
		// Update organization
		try {
			saveLogoImage();
			saveFaviconImage();

			setCustomMessages();
			organizationService.updateOrganization(this.organization);
			
			updateSmptConfiguration(this.appliance);
			
			applianceService.updateAppliance(this.appliance);

			/* Resolv.conf update */
			// saveDnsInformation(); // This will be handled by puppet.
			/* Resolv.conf update */
		} catch (LdapMappingException ex) {
			log.error("Failed to update organization", ex);
			facesMessages.add(FacesMessage.SEVERITY_ERROR, "Failed to update organization");

			return OxTrustConstants.RESULT_FAILURE;
		}
		
		facesMessages.add(FacesMessage.SEVERITY_INFO, "Organization configuration updated successfully");

		return modify();
	}

	public String getSmtpPassword() {
		return applianceService.getDecryptedSmtpPassword(appliance);		
	}

	public void setSmtpPassword(String smptPassword) {
		applianceService.setEncryptedSmtpPassword(appliance, smptPassword);		
	}

	private void updateSmptConfiguration(GluuAppliance appliance) {
		SmtpConfiguration smtpConfiguration = new SmtpConfiguration();
		smtpConfiguration.setHost(appliance.getSmtpHost());
		smtpConfiguration.setPort(StringHelper.toInteger(appliance.getSmtpPort(), 25));
		smtpConfiguration.setRequiresSsl(StringHelper.toBoolean(appliance.getSmtpRequiresSsl(), false));
		smtpConfiguration.setFromName(appliance.getSmtpFromName());
		smtpConfiguration.setFromEmailAddress(appliance.getSmtpFromEmailAddress());
		smtpConfiguration.setRequiresAuthentication(StringHelper.toBoolean(appliance.getSmtpRequiresAuthentication(), false));
		smtpConfiguration.setUserName(appliance.getSmtpUserName());
		smtpConfiguration.setPassword(appliance.getSmtpPassword());
		
		appliance.setSmtpConfiguration(smtpConfiguration);
	}

	public String verifySmtpConfiguration() {
		log.info("HostName: " + appliance.getSmtpHost() + " Port: " + appliance.getSmtpPort() + " RequireSSL: " + appliance.isRequiresSsl()
				+ " RequireSSL: " + appliance.isRequiresAuthentication());
		log.info("UserName: " + appliance.getSmtpUserName() + " Password: " + applianceService.getDecryptedSmtpPassword(appliance));

		try {
			MailUtils mail = new MailUtils(appliance.getSmtpHost(), appliance.getSmtpPort(), appliance.isRequiresSsl(),
					appliance.isRequiresAuthentication(), appliance.getSmtpUserName(), applianceService.getDecryptedSmtpPassword(appliance));
			mail.sendMail(appliance.getSmtpFromName() + " <" + appliance.getSmtpFromEmailAddress() + ">",
					appliance.getSmtpFromEmailAddress(), "SMTP Server Configuration Verification",
					"SMTP Server Configuration Verification Successful.");
			log.info("Connection Successful");
			facesMessages.add(FacesMessage.SEVERITY_INFO, "SMTP Test succeeded!");

            return OxTrustConstants.RESULT_SUCCESS;
		} catch (AuthenticationFailedException ex) {
			log.error("SMTP Authentication Error: ", ex);
		} catch (MessagingException ex) {
			log.error("SMTP Host Connection Error", ex);
		}

		facesMessages.add(FacesMessage.SEVERITY_ERROR, "Failed to connect to SMTP server");

		return OxTrustConstants.RESULT_FAILURE;
	}

	private String modifyApplliance() {
		if (this.appliance != null) {
			return OxTrustConstants.RESULT_SUCCESS;
		}
		try {
			this.appliance = applianceService.getAppliance();
			if (this.appliance == null) {
				return OxTrustConstants.RESULT_FAILURE;
			}
 
			return OxTrustConstants.RESULT_SUCCESS;
		} catch (Exception ex) {
			log.error("an error occured", ex);

			return OxTrustConstants.RESULT_FAILURE;
		}
	}

	private void setCustomMessages() {
		String[][] customMessages = { { OxTrustConstants.CUSTOM_MESSAGE_LOGIN_PAGE, loginPageCustomMessage },
				{ OxTrustConstants.CUSTOM_MESSAGE_WELCOME_PAGE, welcomePageCustomMessage },
				{ OxTrustConstants.CUSTOM_MESSAGE_TITLE_TEXT, welcomeTitleText } };
		String[] customMessagesArray = organizationService.buildOrganizationCustomMessages(customMessages);
		if (customMessagesArray.length > 0) {
			this.organization.setCustomMessages(customMessagesArray);
		} else {
			this.organization.setCustomMessages(null);
		}
	}

	public String getBuildDate() {
		if (this.buildDate != null) {
			return this.buildDate;
		}

		DateFormat formatter = new SimpleDateFormat("yyyy-MM-dd hh:mm");
		try {
			String buildDate = appInitializer.getGluuBuildDate();
			if (StringHelper.isEmpty(buildDate)) {
				return buildDate;
			}

			final Date date = formatter.parse(buildDate);
			this.buildDate = new SimpleDateFormat("hh:mm MMM dd yyyy").format(date) + " UTC";
		} catch (ParseException e) {
			log.error("Error formating date. Build process is invalid.", e);

		}
		return this.buildDate;
	}

	public String getBuildNumber() {
		if (this.buildNumber != null) {
			return this.buildNumber;
		}

		this.buildNumber = appInitializer.getGluuBuildNumber();
		return this.buildNumber;
	}

	public String cancel() throws Exception {
		cancelLogoImage();
		cancelFaviconImage();
		
		facesMessages.add(FacesMessage.SEVERITY_INFO, "Organization configuration not updated");
		conversationService.endConversation();
		
		return OxTrustConstants.RESULT_SUCCESS;
	}

	public void setCustLogoImage(FileUploadEvent event) {
		UploadedFile uploadedFile = event.getUploadedFile();
		try {
			setCustLogoImageImpl(uploadedFile);
		} finally {
			try {
				uploadedFile.delete();
			} catch (IOException ex) {
				log.error("Failed to remove temporary image", ex);
			}
		}
	}

	private void setCustLogoImageImpl(UploadedFile uploadedFile) {
		removeLogoImage();

		GluuImage newLogoImage = imageService.constructImage(currentPerson, uploadedFile);
		newLogoImage.setStoreTemporary(true);
		newLogoImage.setLogo(true);
		try {
			if (imageService.createImageFiles(newLogoImage)) {
				this.curLogoImage = newLogoImage;
			}

			this.organization.setLogoImage(imageService.getXMLFromGluuImage(newLogoImage));
		} catch (Exception ex) {
			log.error("Failed to store icon image: '{}'", newLogoImage, ex);
		}
	}

	public boolean isCustLogoImageExist() {
		return this.curLogoImage != null;
	}

	public void removeLogoImage() {
		cancelLogoImage();

		this.curLogoImage = null;
		this.organization.setLogoImage(null);
	}

	public void cancelLogoImage() {
		if ((this.curLogoImage != null) && this.curLogoImage.isStoreTemporary()) {
			try {
				imageService.deleteImage(this.curLogoImage);
			} catch (Exception ex) {
				log.error("Failed to delete temporary icon image: '{}'", this.curLogoImage, ex);
			}
		}
	}

	public byte[] getLogoImageThumbData() throws Exception {
		if (this.curLogoImage != null) {
			return imageService.getThumImageData(this.curLogoImage);
		}

		return imageService.getBlankImageData();
	}

	public String getLogoImageSourceName() {
		if (this.curLogoImage != null) {
			return this.curLogoImage.getSourceName();
		}

		return null;
	}

	public void saveLogoImage() {
		// Remove old logo image if user upload new logo
		if ((this.oldLogoImage != null)
				&& ((this.curLogoImage == null) || !this.oldLogoImage.getUuid().equals(this.curLogoImage.getUuid()))) {
			try {
				imageService.deleteImage(this.oldLogoImage);
			} catch (Exception ex) {
				log.error("Failed to remove old icon image: '{}'", this.oldLogoImage, ex);
			}
		}

		// Move added photo to persistent location
		if ((this.curLogoImage != null) && this.curLogoImage.isStoreTemporary()) {
			try {
				imageService.moveLogoImageToPersistentStore(this.curLogoImage);
				this.organization.setLogoImage(imageService.getXMLFromGluuImage(curLogoImage));
			} catch (Exception ex) {
				log.error("Failed to move new icon image to persistence store: '{}'", this.curLogoImage, ex);
			}
		}

		this.oldLogoImage = this.curLogoImage;
	}

	public void setFaviconImage(FileUploadEvent event) {
		UploadedFile uploadedFile = event.getUploadedFile();
		try {
			setFaviconImageImpl(uploadedFile);
		} finally {
			try {
				uploadedFile.delete();
			} catch (IOException ex) {
				log.error("Failed to remove temporary image", ex);
			}
		}
	}

	public void setFaviconImageImpl(UploadedFile uploadedFile) {
		removeFaviconImage();

		GluuImage newFaviconImage = imageService.constructImage(currentPerson, uploadedFile);
		newFaviconImage.setStoreTemporary(true);
		newFaviconImage.setLogo(false);
		try {
			if (imageService.createFaviconImageFiles(newFaviconImage)) {
				this.curFaviconImage = newFaviconImage;
			}

			this.organization.setFaviconImage(imageService.getXMLFromGluuImage(newFaviconImage));
		} catch (Exception ex) {
			log.error("Failed to store favicon image: '{}'", newFaviconImage, ex);
		}
	}

	public boolean isFaviconImageExist() {
		return this.curFaviconImage != null;
	}

	public void removeFaviconImage() {
		cancelFaviconImage();

		this.curFaviconImage = null;
		this.organization.setFaviconImage(null);
	}

	public void cancelFaviconImage() {
		if ((this.curFaviconImage != null) && this.curFaviconImage.isStoreTemporary()) {
			try {
				imageService.deleteImage(this.curFaviconImage);
			} catch (Exception ex) {
				log.error("Failed to delete temporary favicon image: '{}'", this.curFaviconImage, ex);
			}
		}
	}

	public byte[] getFaviconImage() throws Exception {
		if (this.curFaviconImage != null) {
			return imageService.getThumImageData(this.curFaviconImage);
		}

		return imageService.getBlankImageData();
	}

	public String getFaviconImageSourceName() {
		if (this.curFaviconImage != null) {
			return this.curFaviconImage.getSourceName();
		}

		return null;
	}

	public void saveFaviconImage() {
		// Remove old favicon image if user upload new image
		if ((this.oldFaviconImage != null)
				&& ((this.curFaviconImage == null) || !this.oldFaviconImage.getUuid().equals(this.curFaviconImage.getUuid()))) {
			try {
				imageService.deleteImage(this.oldFaviconImage);
			} catch (Exception ex) {
				log.error("Failed to remove old favicon image: '{}'", this.oldFaviconImage, ex);
			}
		}

		// Move added photo to persistent location
		if ((this.curFaviconImage != null) && this.curFaviconImage.isStoreTemporary()) {
			try {
				imageService.moveImageToPersistentStore(this.curFaviconImage);
			} catch (Exception ex) {
				log.error("Failed to move new favicon image to persistence store: '{}'", this.curFaviconImage, ex);
			}
		}

		this.oldFaviconImage = this.curFaviconImage;
	}

	public void removeThemeColor() {
		this.organization.setThemeColor(null);
	}

	public GluuOrganization getOrganization() {
		return organization;
	}

	@PreDestroy
	public void destroy() throws Exception {
		// When user decided to leave form without saving we must remove added
		// logo image from disk
		cancel();
	}

	public String getLoginPageCustomMessage() {
		return loginPageCustomMessage;
	}

	public void setLoginPageCustomMessage(String loginPageCustomMessage) {
		this.loginPageCustomMessage = loginPageCustomMessage;
	}

	public String getWelcomePageCustomMessage() {
		return welcomePageCustomMessage;
	}

	public void setWelcomePageCustomMessage(String welcomePageCustomMessage) {
		this.welcomePageCustomMessage = welcomePageCustomMessage;
	}

	public String getWelcomeTitleText() {
		return welcomeTitleText;
	}

	public void setWelcomeTitleText(String welcomeTitleText) {
		this.welcomeTitleText = welcomeTitleText;
	}

	public GluuAppliance getAppliance() {
		return this.appliance;
	}

	/**
	 * @return the appliances
	 */
	public List<GluuAppliance> getAppliances() {
		return appliances;
	}

	/**
	 * @param appliances the appliances to set
	 */
	public void setAppliances(List<GluuAppliance> appliances) {
		this.appliances = appliances;
	}

}
