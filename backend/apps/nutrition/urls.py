from django.urls import path

from .views import FoodBarcodeView, FoodDetailView, FoodSearchView

app_name = 'nutrition'

urlpatterns = [
    path('foods/search/', FoodSearchView.as_view(), name='food_search'),
    path('foods/barcode/<str:code>/', FoodBarcodeView.as_view(), name='food_barcode'),
    path('foods/<int:food_id>/', FoodDetailView.as_view(), name='food_detail'),
]
