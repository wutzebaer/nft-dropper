package de.peterspace.nftdropper.cardano;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;

import org.apache.commons.lang3.StringUtils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProcessUtil {

	public static String runCommand(String... cmd) throws Exception {
		log.trace(StringUtils.join(cmd, " "));
		Process process = Runtime.getRuntime().exec(cmd);

		StringWriter inputStringWriter = logAndCapture(process.getInputStream());
		StringWriter errorStringWriter = logAndCapture(process.getErrorStream());

		int returnCode = process.waitFor();
		if (returnCode != 0) {
			synchronized (errorStringWriter) {
				throw new Exception(errorStringWriter.toString());
			}
		} else {
			synchronized (inputStringWriter) {
				return new String(inputStringWriter.toString()).trim();
			}
		}
	}

	private static StringWriter logAndCapture(InputStream is) {
		StringWriter sw = new StringWriter();
		BufferedReader reader = new BufferedReader(new InputStreamReader(is));
		new Thread(() -> {
			synchronized (sw) {
				String line;
				try {
					while ((line = reader.readLine()) != null) {
					log.trace(line);
						sw.append(line);
					}
				} catch (IOException e) {
					log.error("Reading the stream failed", e);
				}
			}
		}).start();
		return sw;
	}

}
