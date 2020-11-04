package de.symeda.sormas.backend.docgeneration;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.ejb.EJB;
import javax.ejb.Stateless;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;

import de.symeda.sormas.api.EntityDto;
import de.symeda.sormas.api.EntityDtoAccessHelper;
import de.symeda.sormas.api.ReferenceDto;
import de.symeda.sormas.api.docgeneneration.DocumentTemplateFacade;
import de.symeda.sormas.api.docgeneneration.DocumentWorkflow;
import de.symeda.sormas.api.facility.FacilityReferenceDto;
import de.symeda.sormas.api.i18n.I18nProperties;
import de.symeda.sormas.api.i18n.Strings;
import de.symeda.sormas.api.infrastructure.PointOfEntryReferenceDto;
import de.symeda.sormas.api.person.PersonReferenceDto;
import de.symeda.sormas.api.region.CommunityReferenceDto;
import de.symeda.sormas.api.region.DistrictReferenceDto;
import de.symeda.sormas.api.region.RegionReferenceDto;
import de.symeda.sormas.api.user.UserReferenceDto;
import de.symeda.sormas.backend.common.ConfigFacadeEjb;
import de.symeda.sormas.backend.facility.FacilityFacadeEjb;
import de.symeda.sormas.backend.infrastructure.PointOfEntryFacadeEjb;
import de.symeda.sormas.backend.person.PersonFacadeEjb;
import de.symeda.sormas.backend.region.CommunityFacadeEjb;
import de.symeda.sormas.backend.region.DistrictFacadeEjb;
import de.symeda.sormas.backend.region.RegionFacadeEjb;
import de.symeda.sormas.backend.user.UserFacadeEjb;
import fr.opensagres.xdocreport.core.XDocReportException;

@Stateless(name = "DocumentTemplateFacade")
public class DocumentTemplateFacadeEjb implements DocumentTemplateFacade {

	private static final String DEFAULT_NULL_REPLACEMENT = "./.";
	private static final Pattern BASENAME_PATTERN = Pattern.compile("^([^.]+)([.].*)?");

	@EJB
	private ConfigFacadeEjb.ConfigFacadeEjbLocal configFacade;

	@EJB
	private PersonFacadeEjb.PersonFacadeEjbLocal personFacade;

	@EJB
	private UserFacadeEjb.UserFacadeEjbLocal userFacade;

	@EJB
	private RegionFacadeEjb.RegionFacadeEjbLocal regionFacade;

	@EJB
	private DistrictFacadeEjb.DistrictFacadeEjbLocal districtFacade;

	@EJB
	private CommunityFacadeEjb.CommunityFacadeEjbLocal communityFacade;

	@EJB
	private FacilityFacadeEjb.FacilityFacadeEjbLocal facilityFacade;

	@EJB
	private PointOfEntryFacadeEjb.PointOfEntryFacadeEjbLocal pointOfEntryFacade;

	private TemplateEngine templateEngine = new TemplateEngine();

	@Override
	public byte[] generateDocumentFromEntities(
		DocumentWorkflow documentWorkflow,
		String templateName,
		Map<String, EntityDto> entities,
		Properties extraProperties)
		throws IOException {
		// 1. Read template from custom directory
		File templateFile = getTemplateFile(documentWorkflow, templateName);

		// 2. Extract document variables
		Set<String> propertyKeys = getTemplateVariables(templateFile);

		Properties properties = new Properties();

		// 3. Map template variables to case data if possible
		// Naming conventions according sormas-api/src/main/resources/doc/SORMAS_Data_Dictionary.xlsx, e.g.:
		// Case.person.firstName
		// Case.quarantineFrom
		// Generic access as implemented in DataDictionaryGenerator.java

		EntityDtoAccessHelper.IReferenceDtoResolver referenceDtoResolver = getReferenceDtoResolver();

		for (String propertyKey : propertyKeys) {
			if (isEntityVariable(documentWorkflow, propertyKey)) {
				String variableBaseName = getVariableBaseName(propertyKey);
				EntityDto entityDto = entities.get(variableBaseName);
				if (entityDto != null) {
					String propertyPath = propertyKey.replaceFirst(variableBaseName + ".", "");
					String propertyValue = EntityDtoAccessHelper.getPropertyPathValueString(entityDto, propertyPath, referenceDtoResolver);
					properties.setProperty(propertyKey, propertyValue);
				}
			}
		}

		// 3. merge extra properties

		if (extraProperties != null) {
			for (String extraPropertyKey : extraProperties.stringPropertyNames()) {
				String propertyValue = extraProperties.getProperty(extraPropertyKey);
				properties.setProperty(extraPropertyKey, propertyValue);
			}
		}

		// 4. fill null properties
		for (String propertyKey : propertyKeys) {
			if (StringUtils.isBlank(properties.getProperty(propertyKey))) {
				properties.setProperty(propertyKey, DEFAULT_NULL_REPLACEMENT);
			}
		}

		// 5. generate document

		return getGenerateDocument(templateFile, properties);
	}

	@Override
	public byte[] generateDocument(DocumentWorkflow documentWorkflow, String templateName, Properties properties) throws IOException {
		File templateFile = getTemplateFile(documentWorkflow, templateName);
		return getGenerateDocument(templateFile, properties);
	}

	private byte[] getGenerateDocument(File templateFile, Properties properties) throws IOException {
		try {
			return IOUtils.toByteArray(templateEngine.generateDocument(properties, new FileInputStream(templateFile)));
		} catch (XDocReportException e) {
			throw new RuntimeException(String.format(I18nProperties.getString(Strings.errorDocumentGeneration), e.getMessage()));
		}
	}

	@Override
	public List<String> getAvailableTemplates(DocumentWorkflow documentWorkflow) {
		String workflowTemplateDirPath = getWorkflowTemplateDirPath(documentWorkflow);
		File workflowTemplateDir = new File(workflowTemplateDirPath);
		if (!workflowTemplateDir.exists() || !workflowTemplateDir.isDirectory()) {
			return Collections.emptyList();
		}
		File[] availableTemplates =
			workflowTemplateDir.listFiles((d, name) -> name.toLowerCase().endsWith("." + documentWorkflow.getFileExtension()));
		if (availableTemplates == null) {
			return Collections.emptyList();
		}
		return Arrays.stream(availableTemplates).map(File::getName).sorted(String::compareTo).collect(Collectors.toList());
	}

	@Override
	public boolean isExistingTemplate(DocumentWorkflow documentWorkflow, String templateName) {
		String workflowTemplateDirPath = getWorkflowTemplateDirPath(documentWorkflow);
		String templateFileName = workflowTemplateDirPath + File.separator + templateName;
		File templateFile = new File(templateFileName);
		return templateFile.exists();
	}

	@Override
	public List<String> getAdditionalVariables(DocumentWorkflow documentWorkflow, String templateName) throws IOException {
		File templateFile = getTemplateFile(documentWorkflow, templateName);
		Set<String> propertyKeys = getTemplateVariables(templateFile);
		return propertyKeys.stream().filter(e -> !isEntityVariable(documentWorkflow, e)).sorted(String::compareTo).collect(Collectors.toList());
	}

	@Override
	public void writeDocumentTemplate(DocumentWorkflow documentWorkflow, String templateName, byte[] document) throws IOException {
		if (!documentWorkflow.getFileExtension().equalsIgnoreCase(FilenameUtils.getExtension(templateName))) {
			throw new IllegalArgumentException(I18nProperties.getString(Strings.headingWrongFileType));
		}
		String path = FilenameUtils.getPath(templateName);
		if (StringUtils.isNotBlank(path)) {
			throw new IllegalArgumentException(String.format(I18nProperties.getString(Strings.errorIllegalFilename), templateName));
		}

		String workflowTemplateDirPath = getWorkflowTemplateDirPath(documentWorkflow);
		templateEngine.validateTemplate(new ByteArrayInputStream(document));

		Files.createDirectories(Paths.get(workflowTemplateDirPath));
		try (FileOutputStream fileOutputStream =
			new FileOutputStream(workflowTemplateDirPath + File.separator + FilenameUtils.getName(templateName))) {
			fileOutputStream.write(document);
		}
	}

	@Override
	public boolean deleteDocumentTemplate(DocumentWorkflow documentWorkflow, String fileName) {
		String workflowTemplateDirPath = getWorkflowTemplateDirPath(documentWorkflow);
		File templateFile = new File(workflowTemplateDirPath + File.separator + fileName);
		if (templateFile.exists() && templateFile.isFile()) {
			return templateFile.delete();
		} else {
			throw new IllegalArgumentException(String.format(I18nProperties.getString(Strings.errorFileNotFound), fileName));
		}
	}

	@Override
	public byte[] getDocumentTemplate(DocumentWorkflow documentWorkflow, String templateName) throws IOException {
		return FileUtils.readFileToByteArray(getTemplateFile(documentWorkflow, templateName));
	}

	private File getTemplateFile(DocumentWorkflow documentWorkflow, String templateName) {
		String workflowTemplateDirPath = getWorkflowTemplateDirPath(documentWorkflow);
		String templateFileName = workflowTemplateDirPath + File.separator + templateName;
		File templateFile = new File(templateFileName);

		if (!templateFile.exists()) {
			throw new IllegalArgumentException(String.format(I18nProperties.getString(Strings.errorFileNotFound), templateName));
		}
		return templateFile;
	}

	private Set<String> getTemplateVariables(File templateFile) throws IOException {
		try {
			return templateEngine.extractTemplateVariables(new FileInputStream(templateFile));
		} catch (XDocReportException e) {
			throw new RuntimeException(String.format(I18nProperties.getString(Strings.errorProcessingTemplate), templateFile.getName()));
		}
	}

	private boolean isEntityVariable(DocumentWorkflow documentWorkflow, String propertyKey) {
		if (propertyKey == null) {
			return false;
		}
		String basename = getVariableBaseName(propertyKey);
		return documentWorkflow.getRootEntityNames().contains(basename);
	}

	private String getVariableBaseName(String propertyKey) {
		String propertyKeyLowerCase = propertyKey.toLowerCase();
		Matcher matcher = BASENAME_PATTERN.matcher(propertyKeyLowerCase);
		return matcher.matches() ? matcher.group(1) : "";
	}

	private String getWorkflowTemplateDirPath(DocumentWorkflow documentWorkflow) {
		return configFacade.getCustomFilesPath() + File.separator + "docgeneration" + File.separator + documentWorkflow.getTemplateDirectory();
	}

	private EntityDtoAccessHelper.IReferenceDtoResolver getReferenceDtoResolver() {
		EntityDtoAccessHelper.IReferenceDtoResolver referenceDtoResolver = referenceDto -> {
			if (referenceDto != null) {
				String uuid = referenceDto.getUuid();
				Class<? extends ReferenceDto> referenceDtoClass = referenceDto.getClass();
				if (PersonReferenceDto.class.isAssignableFrom(referenceDtoClass)) {
					return personFacade.getPersonByUuid(uuid);
				} else if (UserReferenceDto.class.isAssignableFrom(referenceDtoClass)) {
					return userFacade.getByUuid(uuid);
				} else if (RegionReferenceDto.class.isAssignableFrom(referenceDtoClass)) {
					return regionFacade.getRegionByUuid(uuid);
				} else if (DistrictReferenceDto.class.isAssignableFrom(referenceDtoClass)) {
					return districtFacade.getDistrictByUuid(uuid);
				} else if (CommunityReferenceDto.class.isAssignableFrom(referenceDtoClass)) {
					return communityFacade.getByUuid(uuid);
				} else if (FacilityReferenceDto.class.isAssignableFrom(referenceDtoClass)) {
					return facilityFacade.getByUuid(uuid);
				} else if (PointOfEntryReferenceDto.class.isAssignableFrom(referenceDtoClass)) {
					return pointOfEntryFacade.getByUuid(uuid);
				}
			}
			return null;
		};
		return new EntityDtoAccessHelper.CachedReferenceDtoResolver(referenceDtoResolver);
	}
}
