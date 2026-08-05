package happy.jayden.yang.agentbuilder.infrastructure.catalog;

final class DefaultProfileSql {
  static final String FIND_ACTIVE =
      "SELECT profile.defaults_payload::text FROM default_profile_active_pointer pointer JOIN default_profile_catalog profile ON profile.profile_key=pointer.profile_key AND profile.version=pointer.version AND profile.application_key=pointer.application_key WHERE pointer.application_key=?";
  static final String INSERT_POINTER =
      "INSERT INTO default_profile_active_pointer(application_key,profile_key,version,revision) VALUES (?,?,?,1) ON CONFLICT (application_key) DO NOTHING";
  static final String UPDATE_POINTER =
      "UPDATE default_profile_active_pointer SET profile_key=?,version=?,revision=?,updated_at=CURRENT_TIMESTAMP WHERE application_key=? AND revision=?";
  static final String UPDATE_DRAFT =
      "UPDATE default_profile_catalog SET application_key=?,status=?,revision=?,checksum=?,defaults=?::jsonb,defaults_payload=?::jsonb,tags=?::text[] WHERE profile_key=? AND version=? AND revision=? AND status='DRAFT'";

  private DefaultProfileSql() {}
}
