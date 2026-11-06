"""
Sugeneruoja naują API raktą Atsidėk JSON API (/api/**).

Raktas (slaptas) -> ai/.env kaip ATSIDEK_API_KEY.
Maiša (ne slapta) -> serverio .env kaip API_KEY_HASHES (kelios – kableliais).
"""
import hashlib
import secrets

raktas = "atsk_" + secrets.token_urlsafe(32)
maisa = hashlib.sha256(raktas.encode("utf-8")).hexdigest()

print("ai/.env:        ATSIDEK_API_KEY=" + raktas)
print("serverio .env:  API_KEY_HASHES=" + maisa)
