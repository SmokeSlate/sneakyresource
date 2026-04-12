package org.smokeslate.sneakyresource;

final class MissingRepositoryException extends IllegalStateException {
    MissingRepositoryException(final String message) {
        super(message);
    }
}
