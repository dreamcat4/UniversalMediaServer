package net.pms.dlna;

import java.io.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import net.pms.configuration.PmsConfiguration;
import net.pms.PMS;
import net.pms.formats.Format;
import net.pms.formats.FormatFactory;
import net.pms.util.FileUtil;
import net.pms.util.ProcessUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlaylistFolder extends DLNAResource {
	private static final Logger LOGGER = LoggerFactory.getLogger(PlaylistFolder.class);
	private static final PmsConfiguration configuration = PMS.getConfiguration();
	private String name;
	private String uri;
	private boolean valid = true;
	private boolean isweb = false;
	private int defaultContent = Format.VIDEO;

	public File getPlaylistfile() {
		return isweb ? null : new File(uri);
	}

	public PlaylistFolder(String name, String uri, int type) {
		this.name = name;
		this.uri = uri;
		isweb = FileUtil.isUrl(uri);
		setLastModified(isweb ? 0 : getPlaylistfile().lastModified());
		defaultContent = (type != 0 && type != Format.UNKNOWN) ? type : Format.VIDEO;
	}

	public PlaylistFolder(File f) {
		name = f.getName();
		uri = f.getAbsolutePath();
		isweb = false;
		setLastModified(f.lastModified());
	}

	@Override
	public InputStream getInputStream() throws IOException {
		return null;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public String getSystemName() {
		return isweb ? uri : ProcessUtil.getShortFileNameIfWideChars(uri);
	}

	@Override
	public boolean isFolder() {
		return true;
	}

	@Override
	public boolean isValid() {
		return valid;
	}

	@Override
	public long length() {
		return 0;
	}

	private BufferedReader getBufferedReader() throws IOException {
		if (isweb) {
			return new BufferedReader(new InputStreamReader(new URL(uri).openStream()));
		} else {
			File playlistfile = new File(uri);
			if (playlistfile.length() < 10000000) {
				return new BufferedReader(new FileReader(playlistfile));
			}
		}
		return null;
	}

	@Override
	protected void resolveOnce() {
		ArrayList<Entry> entries = new ArrayList<>();
		boolean m3u = false;
		boolean pls = false;
		try (BufferedReader br = getBufferedReader()) {
			String line;
			while (!m3u && !pls && (line = br.readLine()) != null) {
				line = line.trim();
				if (line.startsWith("#EXTM3U")) {
					m3u = true;
					LOGGER.debug("Reading m3u playlist: " + getName());
				} else if (line.length() > 0 && line.equals("[playlist]")) {
					pls = true;
					LOGGER.debug("Reading PLS playlist: " + getName());
				}
			}
			String fileName;
			String title = null;
			while ((line = br.readLine()) != null) {
				line = line.trim();
				if (pls) {
					if (line.length() > 0 && !line.startsWith("#")) {
						int eq = line.indexOf('=');
						if (eq != -1) {
							String value = line.substring(eq + 1);
							String var = line.substring(0, eq).toLowerCase();
							fileName = null;
							title = null;
							int index = 0;
							if (var.startsWith("file")) {
								index = Integer.valueOf(var.substring(4));
								fileName = value;
							} else if (var.startsWith("title")) {
								index = Integer.valueOf(var.substring(5));
								title = value;
							}
							if (index > 0) {
								while (entries.size() < index) {
									entries.add(null);
								}
								Entry entry = entries.get(index - 1);
								if (entry == null) {
									entry = new Entry();
									entries.set(index - 1, entry);
								}
								if (fileName != null) {
									entry.fileName = fileName;
								}
								if (title != null) {
									entry.title = title;
								}
							}
						}
					}
				} else if (m3u) {
					if (line.startsWith("#EXTINF:")) {
						line = line.substring(8).trim();
						if (line.matches("^-?\\d+,.+")) {
							title = line.substring(line.indexOf(',') + 1).trim();
						} else {
							title = line;
						}
					} else if (!line.startsWith("#") && !line.matches("^\\s*$")) {
						// Non-comment and non-empty line contains the filename
						fileName = line;
						Entry entry = new Entry();
						entry.fileName = fileName;
						entry.title = title;
						entries.add(entry);
						title = null;
					}
				}
			}
		} catch (NumberFormatException | IOException e) {
			LOGGER.error(null, e);
		}

		for (Entry entry : entries) {
			if (entry == null) {
				continue;
			}
			if (entry.title == null) {
				entry.title = new File(entry.fileName).getName();
			}
			LOGGER.debug("Adding " + (pls ? "PLS " : (m3u ? "M3U " : "")) + "entry: " + entry);
			if (! isweb && ! FileUtil.isUrl(entry.fileName)) {
				File en1 = new File(getPlaylistfile().getParentFile(), entry.fileName);
				File en2 = new File(entry.fileName);
				if (en1.exists()) {
					addChild(new RealFile(en1, entry.title));
					valid = true;
				} else {
					if (en2.exists()) {
						addChild(new RealFile(en2, entry.title));
						valid = true;
					}
				}
			} else {
				Format f = FormatFactory.getAssociatedFormat("." + FileUtil.getUrlExtension(entry.fileName));
				int type = f == null ? defaultContent : f.getType();
				String u = FileUtil.urlJoin(uri, entry.fileName);
				DLNAResource d =
					type == Format.VIDEO ? new WebVideoStream(entry.title, u, null) :
					type == Format.AUDIO ? new WebAudioStream(entry.title, u, null) :
					type == Format.IMAGE ? new FeedItem(entry.title, u, null, null, Format.IMAGE) : null;
				if (d != null) {
					addChild(d);
					valid = true;
				}
			}
		}
		if (! isweb) {
			PMS.get().storeFileInCache(getPlaylistfile(), Format.PLAYLIST);
		}

		if (configuration.getSortMethod() == 5) {
			Collections.shuffle(getChildren());
		}

		for (DLNAResource r : getChildren()) {
			r.resolve();
		}
	}

	private static class Entry {
		public String fileName;
		public String title;

		@Override
		public String toString() {
			return "[" + fileName + "," + title + "]";
		}
	}

	public static DLNAResource getPlaylist(String name, String uri, int type) {
		Format f = FormatFactory.getAssociatedFormat("." + FileUtil.getUrlExtension(uri));
		if (f != null && f.getType() == Format.PLAYLIST) {
			switch (f.getMatchedExtension()) {
				case "m3u":
				case "m3u8":
				case "pls":
					return new PlaylistFolder(name, uri, type);
				case "cue":
					return FileUtil.isUrl(uri) ? null : new CueFolder(new File(uri));
			}
		}
		return null;
	}
}
