from django.urls import path

from .views import AllergenListView, MyHealthView

app_name = 'health'

urlpatterns = [
    path('allergens/', AllergenListView.as_view(), name='allergens'),
    path('me/health/', MyHealthView.as_view(), name='my_health'),
]
