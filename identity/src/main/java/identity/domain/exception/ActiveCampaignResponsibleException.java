package identity.domain.exception;

/** No se quita ni se degrada a un responsable activo de una convocatoria sin reemplazarlo antes (ADR-049 D7–D8; Carlos). */
public class ActiveCampaignResponsibleException extends RuntimeException {
    public ActiveCampaignResponsibleException(String message) {
        super(message);
    }
}
