{
    'name': 'NZQueenBee Label Printing',
    'version': '19.0.1.0.0',
    'summary': 'Mobile-friendly EZPL labels and an on-site, outbound-only print queue',
    'license': 'LGPL-3',
    'author': 'NZQueenBee',
    'category': 'Inventory/Inventory',
    'depends': ['stock', 'product_expiry', 'mail'],
    'external_dependencies': {'python': ['PIL']},
    'data': ['security/security.xml', 'security/ir.model.access.csv', 'data/data.xml', 'views/views.xml'],
    'application': True,
    'installable': True,
}
