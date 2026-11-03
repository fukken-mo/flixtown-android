import importlib.util
import sqlite3
import tempfile
import unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('exporter',Path(__file__).with_name('export.py'))
e=importlib.util.module_from_spec(spec);spec.loader.exec_module(e)

class ExportTest(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name);(self.root/'introskipper').mkdir()
  with sqlite3.connect(self.root/'jellyfin.db') as db:
   db.executescript("CREATE TABLE BaseItems(Id,SeriesId,Path,ParentIndexNumber,IndexNumber,RunTimeTicks);CREATE TABLE BaseItemProviders(ItemId,ProviderId,ProviderValue);INSERT INTO BaseItems VALUES('ABC','show','/media/series/1883/Season 1/E1.mkv',1,1,6000000000);INSERT INTO BaseItemProviders VALUES('show','Tmdb','118357');")
  with self.db() as db:
   db.executescript("CREATE TABLE Segments(ItemId,Type,State,StartTicks,EndTicks);CREATE TABLE DisabledItems(ItemId);INSERT INTO Segments VALUES('abc',0,0,200000000,800000000);")
 def db(self):return sqlite3.connect(self.root/'introskipper/introskipper-v2.db')
 def tearDown(self):self.temp.cleanup()
 def test_export(self):
  m=e.export(self.root)['markers'][0];self.assertEqual(m,dict(tmdb='118357',season=1,episode=1,start_ms=20000,end_ms=80000,duration_ms=600000))
 def test_tombstone(self):
  with self.db() as db:db.execute('UPDATE Segments SET State=1')
  with self.assertRaises(ValueError):e.export(self.root)
 def test_disabled(self):
  with self.db() as db:db.execute("INSERT INTO DisabledItems VALUES('ABC')")
  with self.assertRaises(ValueError):e.export(self.root)
 def test_ambiguous(self):
  with self.db() as db:db.execute("INSERT INTO Segments VALUES('abc',0,0,300000000,900000000)")
  with self.assertRaises(ValueError):e.export(self.root)
 def test_invalid_range(self):
  with self.db() as db:db.execute('UPDATE Segments SET EndTicks=9000000000')
  with self.assertRaises(ValueError):e.export(self.root)
 def test_wrong_kind(self):
  with self.db() as db:db.execute('UPDATE Segments SET Type=1')
  with self.assertRaises(ValueError):e.export(self.root)
if __name__=='__main__':unittest.main()
