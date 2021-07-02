#!/bin/zsh
curl -v -u 'admin:$w00pMaimBill' -H "X-Killbill-ApiKey: swoop" -H "X-Killbill-ApiSecret: swoop" -H "Content-Type: application/json" -H 'X-Killbill-CreatedBy: admin' -X POST \
     --data-binary '[
  {
    "tax_zone": "AU",
    "product_name": "HardwareProduct",
    "tax_code": "GST",
    "tax_rate": "0.10",
    "valid_from_date": "2000-10-01T00:00:00+10:00"
  },
  {
    "tax_zone": "AU",
    "product_name": "InstallProduct",
    "tax_code": "GST",
    "tax_rate": "0.10",
    "valid_from_date": "2000-10-01T00:00:00+10:00"
  },
  {
    "tax_zone": "AU",
    "product_name": "NetworkAccessProduct",
    "tax_code": "GST",
    "tax_rate": "0.10",
    "valid_from_date": "2000-10-01T00:00:00+10:00"
  },
  {
    "tax_zone": "AU",
    "product_name": "OtherProduct",
    "tax_code": "GST",
    "tax_rate": "0.10",
    "valid_from_date": "2000-10-01T00:00:00+10:00"
  }
]' \
     "https://maimbill.swoop.com.au:8443/plugins/killbill-easytax/taxCodes"
