from django.contrib import admin

from .models import AccessLog


@admin.register(AccessLog)
class AccessLogAdmin(admin.ModelAdmin):
    """접속기록은 읽기 전용(추가·수정·삭제 불가). 월 1회 점검(런북 §10)"""
    list_display = ('at', 'actor', 'ip', 'action', 'target_type', 'target_id', 'subject_id', 'status')
    list_filter = ('action', 'target_type')
    search_fields = ('actor', 'subject_id', 'target_id', 'ip')
    date_hierarchy = 'at'
    readonly_fields = [f.name for f in AccessLog._meta.fields]

    def has_add_permission(self, request):
        return False

    def has_change_permission(self, request, obj=None):
        return False

    def has_delete_permission(self, request, obj=None):
        return False
