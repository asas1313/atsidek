package eu.atsidek.ai;

/** Laisvo teksto → struktūros ištraukimas. Modelis tik supranta tekstą, mokesčių neskaičiuoja. */
public interface Extractor {

    Extraction extract(String text) throws ExtractionException;

    class ExtractionException extends Exception {
        public ExtractionException(String message, Throwable cause) {
            super(message, cause);
        }

        public ExtractionException(String message) {
            super(message);
        }
    }
}
