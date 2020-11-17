package de.symeda.sormas.backend.docgeneration;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.ejb.EJB;
import javax.ejb.Stateless;

import de.symeda.sormas.api.action.ActionCriteria;
import de.symeda.sormas.api.docgeneneration.DocumentWorkflow;
import de.symeda.sormas.api.docgeneneration.EventDocumentFacade;
import de.symeda.sormas.api.event.EventReferenceDto;
import de.symeda.sormas.backend.action.ActionFacadeEjb.ActionFacadeEjbLocal;
import de.symeda.sormas.backend.docgeneration.DocumentTemplateFacadeEjb.DocumentTemplateFacadeEjbLocal;
import de.symeda.sormas.backend.event.EventParticipantFacadeEjb.EventParticipantFacadeEjbLocal;

@Stateless(name = "EventDocumentFacade")
public class EventDocumentFacadeEjb implements EventDocumentFacade {

	private static final DocumentWorkflow DOCUMENT_WORKFLOW = DocumentWorkflow.EVENT_HANDOUT;

	@EJB
	DocumentTemplateFacadeEjbLocal documentTemplateFacade;

	@EJB
	ActionFacadeEjbLocal actionFacade;

	@EJB
	EventParticipantFacadeEjbLocal eventParticipantFacade;

	@Override
	public String getGeneratedDocument(String templateName, EventReferenceDto eventReference, Properties extraProperties) throws IOException {
		Map<String, Object> entities = new HashMap<>();
		entities.put("event", eventReference);

		ActionCriteria actionCriteria = new ActionCriteria().event(eventReference);
		entities.put("eventActions", actionFacade.getActionList(actionCriteria, null, null));

		entities.put("eventParticipants", eventParticipantFacade.getAllActiveEventParticipantsByEvent(eventReference.getUuid()));

		return documentTemplateFacade.generateDocumentTxtFromEntities(DOCUMENT_WORKFLOW, templateName, entities, extraProperties);
	}

	@Override
	public List<String> getAvailableTemplates() {
		return documentTemplateFacade.getAvailableTemplates(DOCUMENT_WORKFLOW);
	}

	@Override
	public List<String> getAdditionalVariables(String templateName) throws IOException {
		return documentTemplateFacade.getAdditionalVariables(DOCUMENT_WORKFLOW, templateName);
	}
}
