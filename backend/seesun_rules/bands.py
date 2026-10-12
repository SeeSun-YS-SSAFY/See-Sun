"""구간표 조회 공용 함수"""
from decimal import ROUND_HALF_UP, Decimal


def in_band(value, lo, hi, lo_incl, hi_incl) -> bool:
    if lo is not None and (value < lo or (value == lo and not lo_incl)):
        return False
    if hi is not None and (value > hi or (value == hi and not hi_incl)):
        return False
    return True


def lookup(value, bands, default=None):
    """``(하한, 상한, 하한 포함, 상한 포함, 결과)`` 목록에서 처음 맞는 결과를 돌려준다."""
    for lo, hi, lo_incl, hi_incl, result in bands:
        if in_band(value, lo, hi, lo_incl, hi_incl):
            return result
    return default


def round_half_up(value, digits=0):
    """사사오입(파이썬 round()는 은행가 반올림이라 쓰지 않는다)"""
    quant = Decimal(1).scaleb(-digits)
    return float(Decimal(str(value)).quantize(quant, rounding=ROUND_HALF_UP)) if digits else \
        int(Decimal(str(value)).quantize(Decimal(1), rounding=ROUND_HALF_UP))
